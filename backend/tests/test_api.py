import hashlib
import io
import threading
import time
import zipfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from api_fastapi.app import create_app
from kei_core.config import Settings
from kei_core.ocr import PassthroughOCR
from kei_core.translate import Translator

PAGES = Path(__file__).resolve().parents[2] / "test"


class FakeTranslator(Translator):
    def translate(self, items):
        return {k: f"EN: {k}" for k in items}


@pytest.fixture
def client(tmp_path):
    s = Settings(library_dir=str(tmp_path / "lib"),
                 cache_dir=str(tmp_path / "cache"), inpainter="opencv",
                 masker="threshold",  # keep tests off the real CTD model
                 api_key="test-key")
    s.save = lambda *a, **k: None  # never touch the user's real config
    app = create_app(s, translator=FakeTranslator(), ocr=PassthroughOCR("jp"))
    with TestClient(app) as c:
        yield c, s, tmp_path


def put_chapter(c, title="Series", number=1.0,
                images=("010.jpg", "011.jpg", "012.jpg"), sid=None):
    files = []
    for i, name in enumerate(images):
        data = (PAGES / name).read_bytes()
        files.append({"idx": i, "ext": "jpg",
                      "sha256": hashlib.sha256(data).hexdigest(),
                      "size": len(data)})
    body = {"chapters": [{"number": number, "title": "ch", "files": files}]}
    if sid is None:
        body["series_title"] = title
    else:
        body["series_id"] = sid
    r = c.post("/api/uploads", json=body)
    assert r.status_code == 200, r.text
    d = r.json()
    sid, cid = d["series_id"], d["chapters"][0]["chapter_id"]
    for i, name in enumerate(images):
        r = c.put(f"/api/chapters/{cid}/pages/{i}/file",
                  files={"file": (name, (PAGES / name).read_bytes(),
                                  "image/jpeg")})
        assert r.status_code == 200, r.text
    return sid, cid


def test_series_crud_and_delete_files(client):
    c, s, tmp = client
    sid, cid = put_chapter(c, title="Original")
    r = c.get("/api/series")
    assert r.json()[0]["title"] == "Original"
    assert r.json()[0]["cover_url"]  # auto cover after chapter completed
    r = c.patch(f"/api/series/{sid}",
                json={"title": "Renamed", "tags": ["a", "b"], "author": "me"})
    assert r.json()["tags"] == ["a", "b"]
    d = c.get(f"/api/series/{sid}").json()
    assert d["title"] == "Renamed" and d["author"] == "me"
    assert d["tags"] == ["a", "b"]
    assert d["chapters"][0]["page_count"] == 3
    assert d["chapters"][0]["present_count"] == 3
    sdir = s.library_path / str(sid)
    assert sdir.exists()
    c.delete(f"/api/series/{sid}")
    assert not sdir.exists()
    assert c.get(f"/api/series/{sid}").status_code == 404


def test_upload_flow(client):
    c, s, tmp = client
    files = []
    for i, name in enumerate(("010.jpg", "011.jpg", "012.jpg")):
        data = (PAGES / name).read_bytes()
        files.append({"idx": i, "ext": "jpg",
                      "sha256": hashlib.sha256(data).hexdigest(),
                      "size": len(data)})
    r = c.post("/api/uploads", json={
        "series_title": "M",
        "chapters": [{"number": 1, "title": "c1", "files": files}]})
    d = r.json()
    assert d["chapters"][0]["missing"] == [0, 1, 2]
    sid, cid = d["series_id"], d["chapters"][0]["chapter_id"]
    for i, name in enumerate(("010.jpg", "011.jpg")):
        assert c.put(f"/api/chapters/{cid}/pages/{i}/file",
                     files={"file": (name, (PAGES / name).read_bytes(),
                                     "image/jpeg")}).status_code == 200
    r = c.post("/api/uploads", json={
        "series_id": sid,
        "chapters": [{"number": 1, "title": "c1", "files": files}]})
    assert r.json()["chapters"][0]["missing"] == [2]

    # unprocessed page falls back to original with pending header
    r = c.get(f"/api/chapters/{cid}/pages/0/image?view=clean")
    assert r.headers["X-Kei-Status"] == "pending"

    # wrong sha
    r = c.put(f"/api/chapters/{cid}/pages/2/file",
              files={"file": ("x.jpg", b"wrong bytes", "image/jpeg")})
    assert r.status_code == 400
    # non-image bytes that DO match a newly declared sha
    garbage = b"definitely not an image"
    gfiles = [{"idx": 2, "ext": "jpg",
               "sha256": hashlib.sha256(garbage).hexdigest(),
               "size": len(garbage)}]
    c.post("/api/uploads", json={
        "series_id": sid,
        "chapters": [{"number": 1, "title": "c1", "files": gfiles}]})
    r = c.put(f"/api/chapters/{cid}/pages/2/file",
              files={"file": ("x.jpg", garbage, "image/jpeg")})
    assert r.status_code == 400
    # bad ext
    bad = [{"idx": 0, "ext": "gif", "sha256": "x" * 64, "size": 5}]
    r = c.post("/api/uploads", json={
        "series_id": sid,
        "chapters": [{"number": 9, "title": "b", "files": bad}]})
    assert r.status_code == 400


def test_archive_upload(client):
    c, s, tmp = client
    cbz = tmp / "ch.cbz"
    with zipfile.ZipFile(cbz, "w") as z:
        z.write(PAGES / "010.jpg", "b.jpg")
        z.write(PAGES / "011.jpg", "a.jpg")
        z.writestr("../evil.jpg", b"evil")
        z.writestr("__MACOSX/x.jpg", b"junk")
        z.writestr(".hidden.jpg", b"junk")
    r = c.post("/api/uploads/archive",
               files={"file": ("ch.cbz", cbz.read_bytes(),
                               "application/octet-stream")},
               data={"chapter_number": "3", "chapter_title": "arch",
                      "series_title": "AS"})
    assert r.status_code == 200, r.text
    cid = r.json()["chapter_id"]
    d = c.get(f"/api/chapters/{cid}").json()
    assert len(d["pages"]) == 2  # only the real images
    assert all(p["status"] == "pending" for p in d["pages"])
    # natural sort: a.jpg -> idx 0
    f = c.app.state.lib.page_file(cid, 0)
    assert f.read_bytes() == (PAGES / "011.jpg").read_bytes()
    assert not list(s.library_path.rglob("*evil*"))


def test_archive_zipbomb(client, monkeypatch):
    import api_fastapi.app as appmod
    c, s, tmp = client
    monkeypatch.setattr(appmod, "MAX_PAGE_BYTES", 10)  # any real image > 10 B
    cbz = tmp / "bomb.cbz"
    with zipfile.ZipFile(cbz, "w") as z:
        z.write(PAGES / "010.jpg", "a.jpg")
    r = c.post("/api/uploads/archive",
               files={"file": ("bomb.cbz", cbz.read_bytes(),
                               "application/octet-stream")},
               data={"chapter_number": "1", "series_title": "Bomb"})
    assert r.status_code == 400
    assert c.get("/api/series").json() == []  # nothing left behind


def test_cover(client):
    c, s, tmp = client
    sid, cid = put_chapter(c)
    r = c.get(f"/api/series/{sid}/cover")
    assert r.status_code == 200
    im = Image.open(io.BytesIO(r.content))
    assert im.format == "JPEG" and im.width <= 480
    # custom cover
    buf = io.BytesIO()
    Image.new("RGB", (64, 64), (255, 0, 0)).save(buf, "PNG")
    r = c.post(f"/api/series/{sid}/cover",
               files={"file": ("c.png", buf.getvalue(), "image/png")})
    assert r.status_code == 200
    im = Image.open(io.BytesIO(
        c.get(f"/api/series/{sid}/cover").content))
    assert im.size == (64, 64)
    # from-page
    r = c.post(f"/api/series/{sid}/cover/from-page",
               json={"chapter_id": cid, "page": 1})
    assert r.status_code == 200


def test_translate_flow(client):
    c, s, tmp = client
    sid, cid = put_chapter(c, images=("010.jpg", "011.jpg"))
    r = c.post(f"/api/chapters/{cid}/translate", json={"action": "start"})
    assert r.json()["state"] == "running"
    deadline = time.time() + 240
    while time.time() < deadline:
        d = c.get(f"/api/chapters/{cid}").json()
        if all(p["status"] == "translated" for p in d["pages"]):
            break
        time.sleep(1)
    else:
        pytest.fail("chapter not translated in time")
    b = c.get(f"/api/chapters/{cid}/pages/0/blocks").json()
    assert b["blocks"]
    assert all(x["translation"].startswith("EN: ") for x in b["blocks"])
    bid = b["blocks"][0]["id"]
    r = c.patch(f"/api/chapters/{cid}/pages/0/blocks/{bid}",
                json={"translation": "hello"})
    assert r.status_code == 200
    r = c.get(f"/api/chapters/{cid}/pages/0/image?view=translated")
    assert r.headers["content-type"] == "image/png"
    im = Image.open(io.BytesIO(r.content))
    assert im.format == "PNG"


def test_chapter_page_rev_tracks_processing_and_translation_edit(client):
    c, _, _ = client
    _, cid = put_chapter(c, images=("010.jpg",))
    assert c.get(f"/api/chapters/{cid}").json()["pages"][0]["rev"] == 0

    c.post(f"/api/chapters/{cid}/focus", json={"page": 0})
    deadline = time.time() + 60
    while time.time() < deadline:
        page = c.get(f"/api/chapters/{cid}").json()["pages"][0]
        if page["status"] in ("processed", "translated"):
            break
        time.sleep(0.1)
    else:
        pytest.fail("page not processed")
    processed_rev = page["rev"]
    assert processed_rev > 0

    blocks = c.get(f"/api/chapters/{cid}/pages/0/blocks").json()["blocks"]
    assert blocks
    time.sleep(0.01)
    r = c.patch(f"/api/chapters/{cid}/pages/0/blocks/{blocks[0]['id']}",
                json={"translation": "edited"})
    assert r.status_code == 200
    edited_rev = c.get(f"/api/chapters/{cid}").json()["pages"][0]["rev"]
    assert edited_rev > processed_rev


def test_progress(client):
    c, s, tmp = client
    sid, cid = put_chapter(c)
    r = c.put(f"/api/progress/{sid}",
              json={"chapter_id": cid, "page": 3, "percent": 0.3,
                    "device_id": "d1", "ts": 100})
    assert r.json()["page"] == 3
    r = c.put(f"/api/progress/{sid}",
              json={"chapter_id": cid, "page": 1, "percent": 0.1,
                    "device_id": "d1", "ts": 50})
    assert r.json()["page"] == 3  # older ts ignored, stored record returned
    assert c.get(f"/api/progress/{sid}").json()["page"] == 3
    cont = c.get("/api/continue").json()
    assert cont[0]["series_id"] == sid
    assert cont[0]["series_title"] == "Series"
    assert cont[0]["cover_url"]


def test_sse_library_event(client):
    # TestClient can't stream this endpoint (never-ending body); test the bus.
    # Live SSE is smoke-checked with curl against uvicorn.
    import asyncio

    from api_fastapi.events import publish, subscribe, unsubscribe
    c, s, tmp = client
    loop = asyncio.new_event_loop()
    threading.Thread(target=loop.run_forever, daemon=True).start()

    async def _sub():
        return subscribe()

    q = asyncio.run_coroutine_threadsafe(_sub(), loop).result()
    try:
        sid, _ = put_chapter(c)
        c.patch(f"/api/series/{sid}", json={"title": "SSE"})
        ev = asyncio.run_coroutine_threadsafe(
            asyncio.wait_for(q.get(), 5), loop).result(6)
        assert ev["type"] == "library"
    finally:
        unsubscribe(q)
        loop.call_soon_threadsafe(loop.stop)


def test_settings_never_leaks_key(client):
    c, s, tmp = client
    r = c.get("/api/settings")
    assert "api_key" not in r.json()
    assert "SEKRET" not in r.text
    assert r.json()["has_key"] is True
    r = c.patch("/api/settings",
                json={"api_key": "SEKRET", "target_lang": "German"})
    assert "api_key" not in r.json()
    assert "SEKRET" not in r.text
    assert r.json()["target_lang"] == "German"


def test_settings_validation(client):
    c, s, tmp = client
    assert c.patch("/api/settings", json={"masker": "bogus"}).status_code == 422
    assert c.patch("/api/settings", json={"mask_dilate": 99}).status_code == 422
    assert c.patch("/api/settings", json={"mask_feather": -1}).status_code == 422
    assert c.patch("/api/settings", json={"mask_thresh": 2}).status_code == 422
    assert c.patch("/api/settings", json={"batch_pages": "abc"}).status_code == 422
    assert c.patch("/api/settings",
                   json={"model_priority": "notalist"}).status_code == 422
    # values are coerced and persisted
    r = c.patch("/api/settings",
                json={"masker": "model", "mask_dilate": "7",
                      "mask_thresh": "0.5"})
    assert r.status_code == 200
    assert r.json()["masker"] == "model"
    assert r.json()["mask_dilate"] == 7
    assert r.json()["mask_thresh"] == 0.5
    assert s.masker == "model" and s.mask_dilate == 7


def test_reprocess_page(client):
    c, s, tmp = client
    sid, cid = put_chapter(c, images=("010.jpg",))
    r = c.post(f"/api/chapters/{cid}/pages/0/reprocess")
    assert r.status_code == 200
    # page is re-queued and gets processed (and translated by the fake)
    deadline = time.time() + 240
    while time.time() < deadline:
        d = c.get(f"/api/chapters/{cid}").json()
        if d["pages"][0]["status"] in ("processed", "translated"):
            break
        time.sleep(0.5)
    else:
        pytest.fail("page not reprocessed")
    assert d["pages"][0]["status"] == "translated"
    assert c.post(f"/api/chapters/{cid}/pages/9/reprocess").status_code == 404
    assert c.post(f"/api/chapters/999/reprocess").status_code == 404
