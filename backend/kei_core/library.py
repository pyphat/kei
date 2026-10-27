import hashlib
import io
import json
import shutil
import sqlite3
import threading
import time
from pathlib import Path

from PIL import Image

EXTS = {"jpg", "jpeg", "png", "webp"}

SCHEMA = """
CREATE TABLE IF NOT EXISTS series(
    id INTEGER PRIMARY KEY,
    title TEXT, author TEXT DEFAULT '', tags TEXT DEFAULT '[]',
    has_cover INT DEFAULT 0, created REAL, updated REAL);
CREATE TABLE IF NOT EXISTS chapter(
    id INTEGER PRIMARY KEY,
    series_id INTEGER REFERENCES series(id) ON DELETE CASCADE,
    number REAL, title TEXT, sort INT, created REAL);
CREATE TABLE IF NOT EXISTS page(
    id INTEGER PRIMARY KEY,
    chapter_id INTEGER REFERENCES chapter(id) ON DELETE CASCADE,
    idx INT, ext TEXT, sha256 TEXT, width INT, height INT,
    present INT DEFAULT 0, UNIQUE(chapter_id, idx));
CREATE TABLE IF NOT EXISTS progress(
    series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
    chapter_id INT, page INT, percent REAL, device_id TEXT, ts REAL);
"""


def norm_ext(name):
    e = Path(name).suffix.lower().lstrip(".")
    e = {"jpeg": "jpg"}.get(e, e)
    return e if e in EXTS else None


def natkey(name):
    import re
    return [int(t) if t.isdigit() else t
            for t in re.split(r"(\d+)", name.lower())]


class Library:
    """SQLite-backed manga library. Files live under root/<sid>/<cid>/;
    clients never see filenames."""

    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.db = sqlite3.connect(self.root / "library.db",
                                  check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        with self._lock:
            self.db.executescript(SCHEMA)
            self.db.execute("PRAGMA journal_mode=WAL")
            self.db.execute("PRAGMA foreign_keys=ON")
            self.db.commit()

    def _q(self, sql, a=()):
        with self._lock:
            return self.db.execute(sql, a).fetchall()

    def _one(self, sql, a=()):
        with self._lock:
            return self.db.execute(sql, a).fetchone()

    def _x(self, sql, a=()):
        with self._lock:
            cur = self.db.execute(sql, a)
            self.db.commit()
            return cur

    # --- series ---

    def create_series(self, title, author="", tags=None):
        t = time.time()
        cur = self._x(
            "INSERT INTO series(title,author,tags,created,updated)"
            " VALUES(?,?,?,?,?)",
            (title, author, json.dumps(tags or []), t, t))
        return cur.lastrowid

    def get_series(self, sid):
        return self._one("SELECT * FROM series WHERE id=?", (sid,))

    def list_series(self):
        return self._q("""
            SELECT s.*,
              (SELECT COUNT(*) FROM chapter c WHERE c.series_id=s.id)
                AS chapter_count,
              (SELECT COUNT(*) FROM page p JOIN chapter c
                ON p.chapter_id=c.id WHERE c.series_id=s.id AND p.present=1)
                AS page_count
            FROM series s ORDER BY s.updated DESC""")

    def update_series(self, sid, title=None, author=None, tags=None):
        sets, args = ["updated=?"], [time.time()]
        if title is not None:
            sets.append("title=?")
            args.append(title)
        if author is not None:
            sets.append("author=?")
            args.append(author)
        if tags is not None:
            sets.append("tags=?")
            args.append(json.dumps(tags))
        args.append(sid)
        self._x(f"UPDATE series SET {','.join(sets)} WHERE id=?", args)

    def delete_series(self, sid):
        self._x("DELETE FROM series WHERE id=?", (sid,))

    def series_page_count(self, sid):
        return self._one(
            "SELECT COUNT(*) n FROM page p JOIN chapter c"
            " ON p.chapter_id=c.id WHERE c.series_id=? AND p.present=1",
            (sid,))["n"]

    def touch_series(self, sid):
        self._x("UPDATE series SET updated=? WHERE id=?", (time.time(), sid))

    # --- chapters ---

    def create_chapter(self, sid, number, title=""):
        nxt = self._one("SELECT COALESCE(MAX(sort),-1)+1 n FROM chapter"
                        " WHERE series_id=?", (sid,))["n"]
        cur = self._x("INSERT INTO chapter(series_id,number,title,sort,"
                      "created) VALUES(?,?,?,?,?)",
                      (sid, number, title, nxt, time.time()))
        self.touch_series(sid)
        return cur.lastrowid

    def get_chapter(self, cid):
        return self._one("SELECT * FROM chapter WHERE id=?", (cid,))

    def get_chapter_by_number(self, sid, number):
        return self._one("SELECT * FROM chapter WHERE series_id=? AND number=?",
                         (sid, number))

    def list_chapters(self, sid):
        return self._q("SELECT * FROM chapter WHERE series_id=?"
                       " ORDER BY sort, number", (sid,))

    def update_chapter(self, cid, title=None, number=None, sort=None):
        sets, args = [], []
        for k, v in (("title", title), ("number", number), ("sort", sort)):
            if v is not None:
                sets.append(f"{k}=?")
                args.append(v)
        if sets:
            args.append(cid)
            self._x(f"UPDATE chapter SET {','.join(sets)} WHERE id=?", args)
            ch = self.get_chapter(cid)
            if ch:
                self.touch_series(ch["series_id"])

    def delete_chapter(self, cid):
        self._x("DELETE FROM chapter WHERE id=?", (cid,))

    # --- pages ---

    def upsert_page(self, cid, idx, ext, sha256):
        self._x("""
            INSERT INTO page(chapter_id,idx,ext,sha256,present)
            VALUES(?,?,?,?,0)
            ON CONFLICT(chapter_id,idx) DO UPDATE SET
                ext=excluded.ext, sha256=excluded.sha256,
                present = CASE WHEN page.sha256=excluded.sha256
                          THEN page.present ELSE 0 END""",
                (cid, idx, ext, sha256))

    def get_page(self, cid, idx):
        return self._one("SELECT * FROM page WHERE chapter_id=? AND idx=?",
                         (cid, idx))

    def pages(self, cid):
        return self._q("SELECT * FROM page WHERE chapter_id=? ORDER BY idx",
                       (cid,))

    def page_indices(self, cid):
        return [p["idx"] for p in self.pages(cid)]

    def mark_page_present(self, cid, idx, w, h):
        self._x("UPDATE page SET present=1,width=?,height=? WHERE"
                " chapter_id=? AND idx=?", (w, h, cid, idx))
        ch = self.get_chapter(cid)
        if ch:
            self.touch_series(ch["series_id"])

    def missing(self, cid):
        out = []
        for p in self.pages(cid):
            f = self.page_path(cid, p["idx"], p["ext"])
            if not p["present"] or not p["sha256"] or not f.exists():
                out.append(p["idx"])
        return out

    def chapter_complete(self, cid):
        ps = self.pages(cid)
        return bool(ps) and all(p["present"] for p in ps)

    # --- files ---

    def series_dir(self, sid):
        return self.root / str(sid)

    def chapter_dir(self, sid, cid):
        return self.series_dir(sid) / str(cid)

    def page_path(self, cid, idx, ext):
        ch = self.get_chapter(cid)
        return self.chapter_dir(ch["series_id"], cid) / f"{idx:04d}.{ext}"

    def page_file(self, cid, idx):
        """The stored file for a page, or None."""
        d = self.chapter_dir(self.get_chapter(cid)["series_id"], cid)
        for f in d.glob(f"{idx:04d}.*"):
            if f.suffix.lstrip(".") in EXTS:
                return f
        return None

    def store_page(self, cid, idx, data):
        """Write data to the page's declared path; returns (width, height)."""
        p = self.get_page(cid, idx)
        ch = self.get_chapter(cid)
        d = self.chapter_dir(ch["series_id"], cid)
        d.mkdir(parents=True, exist_ok=True)
        dest = d / f"{idx:04d}.{p['ext']}"
        for old in d.glob(f"{idx:04d}.*"):  # ext may have changed on re-declare
            if old != dest:
                old.unlink()
        dest.write_bytes(data)
        return dest

    def cover_file(self, sid):
        return self.series_dir(sid) / "cover.jpg"

    def save_cover(self, sid, img: Image.Image):
        self.series_dir(sid).mkdir(parents=True, exist_ok=True)
        img = img.convert("RGB")
        if img.width > 480:
            img = img.resize((480, int(img.height * 480 / img.width)))
        img.save(self.cover_file(sid), "JPEG", quality=88)
        self._x("UPDATE series SET has_cover=1,updated=? WHERE id=?",
                (time.time(), sid))

    # --- progress ---

    def set_progress(self, sid, cid, page, percent, device_id, ts):
        row = self._one("SELECT * FROM progress WHERE series_id=?", (sid,))
        if row and row["ts"] > ts:
            return row
        self._x("INSERT OR REPLACE INTO progress"
                "(series_id,chapter_id,page,percent,device_id,ts)"
                " VALUES(?,?,?,?,?,?)", (sid, cid, page, percent, device_id, ts))
        return self._one("SELECT * FROM progress WHERE series_id=?", (sid,))

    def get_progress(self, sid):
        return self._one("SELECT * FROM progress WHERE series_id=?", (sid,))

    def continue_list(self, limit=10):
        return self._q("""
            SELECT p.series_id, p.chapter_id, p.page, p.percent, p.ts,
                   s.title AS series_title, s.has_cover, s.updated AS s_updated,
                   c.title AS chapter_title, c.number AS chapter_number
            FROM progress p JOIN series s ON s.id=p.series_id
            LEFT JOIN chapter c ON c.id=p.chapter_id
            ORDER BY p.ts DESC LIMIT ?""", (limit,))
