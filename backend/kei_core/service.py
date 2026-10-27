import hashlib
import io
import json
import logging
import re
import shutil
import threading
import time
import zipfile
from collections import OrderedDict
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from . import detect as _detect
from . import inpaint as _inpaint
from . import mask as _mask
from . import ocr as _ocr
from . import typeset as _typeset
from .models import TextBlock
from .translate import OpenRouterTranslator

_IMG_EXT = {".jpg", ".jpeg", ".png", ".webp"}

log = logging.getLogger(__name__)


def _natkey(p):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", p.name)]


class ReaderService:
    def __init__(self, settings, translator=None, ocr=None):
        self.s = settings
        self.translator = translator or OpenRouterTranslator(settings)
        self.ocr = ocr or (lambda crop: _ocr.get_ocr(
            force_cpu=self.device == "cpu")(crop))  # lazy: model loads on first page
        self._lock = threading.Lock()
        self.pages: list[Path] = []
        self._chapter = ""
        self._lru = OrderedDict()  # (page, version) -> PIL translated render
        self._version = {}
        self._sha = {}  # page index -> image sha (memoized; files don't change)
        self._gen = 0  # bumped on open/clear/reset; stale translate batches die

    # --- chapter ---

    def open_chapter(self, path) -> int:
        path = Path(path)
        if path.is_dir():
            pages = sorted([p for p in path.iterdir() if p.suffix.lower() in _IMG_EXT],
                           key=_natkey)
            key = hashlib.sha1((str(path.resolve()) + "|".join(p.name for p in pages))
                               .encode()).hexdigest()[:12]
        elif path.suffix.lower() in (".cbz", ".zip"):
            st = path.stat()
            key = hashlib.sha1(f"{path.resolve()}:{st.st_size}:{st.st_mtime}"
                               .encode()).hexdigest()[:12]
            dest = self.s.cache_path / key / "src"
            if not dest.exists():
                dest.mkdir(parents=True, exist_ok=True)
                with zipfile.ZipFile(path) as z:
                    z.extractall(dest)
            pages = sorted([p for p in dest.rglob("*") if p.suffix.lower() in _IMG_EXT],
                           key=_natkey)
        else:
            raise ValueError(f"not a chapter: {path}")
        with self._lock:
            self.pages = pages
            self._chapter = key
            self._lru.clear()
            self._version.clear()
            self._sha.clear()
            self._gen += 1
        log.info("chapter opened path=%s pages=%d key=%s device=%s",
                 path, len(pages), key, self.device)
        return len(pages)

    @property
    def page_count(self):
        return len(self.pages)

    @property
    def device(self):
        return self.s.resolved_device

    def set_translator(self, t):
        self.translator = t

    def list_free_models(self):
        fn = getattr(self.translator, "list_free_models", None)
        return fn() if fn else []

    def requests_today(self):
        fn = getattr(self.translator, "requests_today", None)
        return fn() if fn else 0

    # --- per-page cache ---

    def _page_dir(self, i) -> Path:
        if i not in self._sha:
            self._sha[i] = hashlib.sha1(
                self.pages[i].read_bytes()).hexdigest()[:12]
        return self.s.cache_path / self._chapter / self._sha[i]

    def _load_state(self, i):
        f = self._page_dir(i) / "blocks.json"
        if not f.exists():
            return [], []
        d = json.loads(f.read_text("utf-8"))
        frames = [tuple(b) for b in d.get("frames", [])]
        blocks = [TextBlock.from_dict(b) for b in d.get("blocks", [])]
        return frames, blocks

    def _save_state(self, i, frames, blocks):
        d = self._page_dir(i)
        d.mkdir(parents=True, exist_ok=True)
        (d / "blocks.json").write_text(json.dumps(
            {"frames": [list(b) for b in frames],
             "blocks": [b.to_dict() for b in blocks]}, ensure_ascii=False), "utf-8")

    def _load_translations(self, i):
        f = self._page_dir(i) / "translations.json"
        return json.loads(f.read_text("utf-8")) if f.exists() else {}

    def _save_translations(self, i, tr):
        d = self._page_dir(i)
        d.mkdir(parents=True, exist_ok=True)
        (d / "translations.json").write_text(json.dumps(tr, ensure_ascii=False), "utf-8")

    def _page_bgr(self, i):
        return cv2.imdecode(np.fromfile(self.pages[i], np.uint8), cv2.IMREAD_COLOR)

    def _is_processed(self, i):
        d = self._page_dir(i)
        return (d / "blocks.json").exists() and (d / "clean.png").exists()

    # --- pipeline ---

    def process_page(self, i, on_progress=None):
        cb = on_progress or (lambda stage: None)
        if self._is_processed(i):
            log.debug("page %d served from cache", i)
            return
        img = self._page_bgr(i)
        cb("detecting")
        t = time.time()
        frames_raw, texts = _detect.detect(img, self.s.det_conf, self.device)
        frames, blocks = _detect.order_blocks(frames_raw, texts, i, self.s.reading_rtl)
        t_det = time.time() - t
        cb("ocr")
        t = time.time()
        page = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        for b in blocks:
            b.source = self.ocr(page.crop(b.bbox))
            log.debug("ocr %s: %s", b.id, b.source)
        t_ocr = time.time() - t
        cb("cleaning")
        t = time.time()
        m = _mask.build_mask(img, [b.bbox for b in blocks])
        clean = _inpaint.clean(img, m, [b.bbox for b in blocks],
                               self.s.inpainter, self.device)
        t_clean = time.time() - t
        log.info("page %d processed blocks=%d detect=%.1fs ocr=%.1fs clean=%.1fs",
                 i, len(blocks), t_det, t_ocr, t_clean)
        with self._lock:
            self._save_state(i, frames, blocks)
            cv2.imwrite(str(self._page_dir(i) / "clean.png"), clean)
            self._bump(i)

    def get_blocks(self, i) -> list[TextBlock]:
        _, blocks = self._load_state(i)
        tr = self._load_translations(i)
        for b in blocks:
            b.translation = tr.get(b.id, "")
        return blocks

    def get_frames(self, i):
        frames, _ = self._load_state(i)
        return frames

    def reset_page(self, i):
        with self._lock:
            shutil.rmtree(self._page_dir(i), ignore_errors=True)
            self._bump(i)
            self._gen += 1
            log.info("reset page %d", i)

    def clear_cache(self):
        """Delete every per-page dir of the current chapter, keeping src/
        (cbz source images) and usage.json."""
        with self._lock:
            if not self._chapter:
                return
            d = self.s.cache_path / self._chapter
            if d.exists():
                for child in d.iterdir():
                    if child.name == "src":
                        continue
                    shutil.rmtree(child) if child.is_dir() else child.unlink()
            self._lru.clear()
            self._version.clear()
            self._gen += 1
            log.info("cleared cache for chapter %s", self._chapter)

    def translate_pending(self, start=0, max_pages=None):
        limit = max_pages or self.s.batch_pages
        items, id2page, updated = {}, {}, []
        with self._lock:
            gen, chapter = self._gen, self._chapter
            for i in range(start, len(self.pages)):
                if len(updated) >= limit:
                    break
                if not self._is_processed(i):
                    continue
                _, blocks = self._load_state(i)
                if not blocks:
                    continue
                have = self._load_translations(i)
                todo = [b for b in blocks if b.source and b.id not in have]
                if not todo:
                    continue
                for b in todo:
                    items[b.id] = b.source
                    id2page[b.id] = i
                updated.append(i)
        if not items:
            return []
        log.info("translate batch pages=%s items=%d", updated, len(items))
        result = self.translator.translate(items)
        with self._lock:
            if gen != self._gen or chapter != self._chapter:
                log.info("discarding stale translation batch")
                return []
            per_page = {}
            for bid, text in result.items():
                per_page.setdefault(id2page[bid], {})[bid] = text
            for i, tr in per_page.items():
                merged = self._load_translations(i)
                merged.update(tr)
                self._save_translations(i, merged)
                self._bump(i)
            log.info("translated pages=%s", sorted(per_page))
            return sorted(per_page)

    # --- views ---

    def _bump(self, i):
        self._version[i] = self._version.get(i, 0) + 1
        for k in [k for k in self._lru if k[0] == i]:
            self._lru.pop(k)

    def get_page(self, i, view="original") -> Image.Image:
        if view == "original" or not self._is_processed(i):
            return Image.open(self.pages[i]).convert("RGB")
        if view == "clean":
            return Image.open(self._page_dir(i) / "clean.png").convert("RGB")
        key = (i, self._version.get(i, 0))
        img = self._lru.get(key)
        if img is None:
            t = time.time()
            blocks = self.get_blocks(i)
            clean = self.get_page(i, "clean")
            img = _typeset.render(clean, blocks, self.s)
            log.debug("render page %d %.2fs", i, time.time() - t)
            with self._lock:
                self._lru[key] = img
                while len(self._lru) > 8:
                    self._lru.popitem(last=False)
        return img

    # --- edits ---

    def update_translation(self, i, block_id, text):
        with self._lock:
            tr = self._load_translations(i)
            tr[block_id] = text
            self._save_translations(i, tr)
            self._bump(i)
            log.debug("update translation %s page %d", block_id, i)

    def delete_block(self, i, block_id):
        with self._lock:
            frames, blocks = self._load_state(i)
            blocks = [b for b in blocks if b.id != block_id]
            img = self._page_bgr(i)
            m = _mask.build_mask(img, [b.bbox for b in blocks])
            clean = _inpaint.clean(img, m, [b.bbox for b in blocks],
                                   self.s.inpainter, self.device)
            self._save_state(i, frames, blocks)
            cv2.imwrite(str(self._page_dir(i) / "clean.png"), clean)
            tr = self._load_translations(i)
            if tr.pop(block_id, None) is not None:
                self._save_translations(i, tr)
            self._bump(i)
            log.info("deleted block %s page %d", block_id, i)

    def add_block(self, i, bbox) -> TextBlock:
        bbox = tuple(int(v) for v in bbox)
        with self._lock:
            frames, blocks = self._load_state(i)
            n = 1 + max((int(b.id.rsplit("_b", 1)[1]) for b in blocks), default=-1)
            block = TextBlock(f"p{i}_b{n}", bbox)
            img = self._page_bgr(i)
            page = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
            block.source = self.ocr(page.crop(bbox))
            base = self._page_dir(i) / "clean.png"
            base_img = (cv2.imdecode(np.fromfile(base, np.uint8), cv2.IMREAD_COLOR)
                        if base.exists() else img)
            m = _mask.build_mask(img, [bbox])
            clean = _inpaint.clean(base_img, m, [bbox],
                                   self.s.inpainter, self.device)
            blocks.append(block)
            self._save_state(i, frames, blocks)
            cv2.imwrite(str(self._page_dir(i) / "clean.png"), clean)
            self._bump(i)
            log.info("added block %s page %d bbox=%s", block.id, i, bbox)
            return block

    # --- export ---

    def export(self, out_path, fmt="cbz"):
        out = Path(out_path)
        imgs = []
        for i in range(len(self.pages)):
            view = "translated" if self._is_processed(i) else "original"
            imgs.append((f"{i:03d}.png", self.get_page(i, view)))
        if fmt == "png":
            out.mkdir(parents=True, exist_ok=True)
            for name, im in imgs:
                im.save(out / name)
        elif fmt == "cbz":
            out.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                for name, im in imgs:
                    buf = io.BytesIO()
                    im.save(buf, "PNG")
                    z.writestr(name, buf.getvalue())
        else:
            raise ValueError(f"unknown fmt: {fmt}")
        log.info("exported %d pages to %s fmt=%s", len(imgs), out, fmt)
