import logging
import shutil
import threading
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

from kei_core.config import Settings
from kei_core.ocr import PassthroughOCR
from kei_core.service import ReaderService
from kei_core.translate import Translator

PAGE = Path(__file__).resolve().parents[2] / "test" / "010.jpg"


class FakeTranslator(Translator):
    def translate(self, items):
        return {k: f"EN:{k}" for k in items}


def make_service(tmp_path):
    chapter = tmp_path / "chapter"
    chapter.mkdir()
    shutil.copy(PAGE, chapter / "001.jpg")
    s = Settings(cache_dir=str(tmp_path / "cache"), inpainter="opencv", masker="threshold")
    svc = ReaderService(s, translator=FakeTranslator(), ocr=PassthroughOCR("jp"))
    assert svc.open_chapter(chapter) == 1
    return svc


def test_end_to_end(tmp_path):
    svc = make_service(tmp_path)
    stages = []
    svc.process_page(0, on_progress=stages.append)
    assert stages == ["detecting", "ocr", "cleaning"]

    blocks = svc.get_blocks(0)
    assert len(blocks) >= 5  # 010.jpg has ~10 text boxes
    assert all(b.source == "jp" for b in blocks)
    assert svc.get_frames(0)

    before = svc.requests_today()
    updated = svc.translate_pending()
    assert updated == [0]
    assert svc.requests_today() == before  # fake translator counts nothing

    img = svc.get_page(0, "translated")
    orig = Image.open(PAGE)
    assert img.size == orig.size
    a1 = np.array(img)

    first = blocks[0].id
    svc.update_translation(0, first, "EDITED")
    a2 = np.array(svc.get_page(0, "translated"))
    assert not np.array_equal(a1, a2)
    assert svc.get_blocks(0)[0].translation == "EDITED"

    out = tmp_path / "out.cbz"
    svc.export(out, "cbz")
    with zipfile.ZipFile(out) as z:
        assert z.namelist() == ["000.png"]


def test_cached_process_is_instant(tmp_path):
    svc = make_service(tmp_path)
    svc.process_page(0)
    calls = []
    svc.ocr = lambda c: calls.append(1) or "x"
    svc.process_page(0)  # cache hit: no re-OCR
    assert not calls


def test_clear_cache_folder(tmp_path):
    svc = make_service(tmp_path)
    svc.process_page(0)
    svc.translate_pending()
    assert svc._is_processed(0)
    usage = svc.s.cache_path / "usage.json"
    usage.write_text("{}")
    svc.clear_cache()
    assert not svc._is_processed(0)
    assert svc.get_blocks(0) == []
    assert usage.exists()


def test_clear_cache_cbz(tmp_path):
    cbz = tmp_path / "ch.cbz"
    with zipfile.ZipFile(cbz, "w") as z:
        z.write(PAGE, "001.jpg")
    s = Settings(cache_dir=str(tmp_path / "cache"), inpainter="opencv", masker="threshold")
    svc = ReaderService(s, translator=FakeTranslator(),
                        ocr=PassthroughOCR("jp"))
    assert svc.open_chapter(cbz) == 1
    src = svc.s.cache_path / svc._chapter / "src"
    assert src.exists()
    svc.process_page(0)
    assert svc._is_processed(0)
    svc.clear_cache()
    assert src.exists()          # cbz page images must survive
    assert not svc._is_processed(0)
    assert svc.get_page(0, "original").size == Image.open(PAGE).size
    svc.process_page(0)
    assert svc._is_processed(0)
    assert svc.get_blocks(0)


class BlockingTranslator(Translator):
    def __init__(self):
        self.gate = threading.Event()
        self.started = threading.Event()

    def translate(self, items):
        self.started.set()
        self.gate.wait(30)
        return {k: f"EN:{k}" for k in items}


def test_stale_translation_discarded(tmp_path, caplog):
    svc = make_service(tmp_path)
    svc.process_page(0)
    svc.set_translator(BlockingTranslator())
    bt = svc.translator
    result = []
    th = threading.Thread(target=lambda: result.append(
        svc.translate_pending()))
    th.start()
    assert bt.started.wait(5)
    svc.clear_cache()
    bt.gate.set()
    with caplog.at_level(logging.INFO, logger="kei_core.service"):
        th.join(5)
    assert not th.is_alive()
    assert result == [[]]
    assert "discarding stale" in caplog.text
    assert svc.get_blocks(0) == []
    assert not (svc._page_dir(0) / "translations.json").exists()


def test_process_during_translate(tmp_path):
    chapter = tmp_path / "chapter"
    chapter.mkdir()
    shutil.copy(PAGE, chapter / "001.jpg")
    shutil.copy(PAGE, chapter / "002.jpg")
    s = Settings(cache_dir=str(tmp_path / "cache"), inpainter="opencv", masker="threshold")
    svc = ReaderService(s, translator=BlockingTranslator(),
                        ocr=PassthroughOCR("jp"))
    svc.open_chapter(chapter)
    svc.process_page(0)
    th = threading.Thread(target=lambda: svc.translate_pending())
    th.start()
    assert svc.translator.started.wait(5)
    svc.process_page(1)  # must not wait on the in-flight translate call
    assert svc._is_processed(1)
    svc.translator.gate.set()
    th.join(5)
    assert not th.is_alive()


def test_delete_and_add_block(tmp_path):
    svc = make_service(tmp_path)
    svc.process_page(0)
    blocks = svc.get_blocks(0)
    n = len(blocks)
    svc.delete_block(0, blocks[0].id)
    assert len(svc.get_blocks(0)) == n - 1
    b = svc.add_block(0, (10, 10, 60, 50))
    assert b.source == "jp"
    assert len(svc.get_blocks(0)) == n
