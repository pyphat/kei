"""GUI smoke: real window, fake translator, PassthroughOCR, opencv inpainter,
tmp cache. Screenshots -> frontend/tkinter_gui/debug_out/. Run from repo root:
    python frontend/tkinter_gui/smoke.py
"""
import sys
import tempfile
import time
import tkinter as tk
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from PIL import ImageGrab

from kei_core.config import Settings
from kei_core.ocr import PassthroughOCR
from kei_core.service import ReaderService
from kei_core.translate import Translator

from frontend.tkinter_gui.app import ReaderApp

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "debug_out"


class FakeTranslator(Translator):
    def translate(self, items):
        return {k: f"EN: {k}" for k in items}


def pump(app, cond, what="", timeout=180):
    t = time.time()
    while not cond():
        app.update()
        time.sleep(0.03)
        if time.time() - t > timeout:
            raise TimeoutError(what)


def grab(app, name):
    app.update_idletasks()
    app.update()
    x, y = app.winfo_rootx(), app.winfo_rooty()
    ImageGrab.grab((x, y, x + app.winfo_width(),
                    y + app.winfo_height())).save(OUT / name)
    print("saved", name)


def main():
    OUT.mkdir(exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="kei_smoke_"))
    s = Settings(cache_dir=str(tmp / "cache"), inpainter="opencv",
                 api_key="fake")
    svc = ReaderService(s, translator=FakeTranslator(),
                        ocr=PassthroughOCR("jp"))
    app = ReaderApp(svc)
    try:
        app.open_path(ROOT / "test")
        pump(app, lambda: len(app.page_sizes) == 25, "open")
        app.auto_tr.set(True)
        app._toggle_translate()
        app.goto(9)  # test/010.jpg
        pump(app, lambda: app.page == 9 and svc.get_blocks(9)
             and all(b.translation for b in svc.get_blocks(9)),
             "page 9 translated")
        app.update_idletasks()

        app.show_boxes.set(True)
        app.select_block(svc.get_blocks(9)[0].id)
        grab(app, "single_translated.png")

        app.mode.set("continuous")
        app._mode_changed()
        app.canvas.yview_moveto(0.5)
        app.canvas.render()
        grab(app, "continuous.png")

        app.mode.set("single")
        app._mode_changed()
        app.goto(9)
        app.view.set("original")
        app.canvas.render()
        grab(app, "single_original.png")

        app.view.set("translated")
        app.canvas.render()
        bid = svc.get_blocks(9)[0].id
        row = app.panel.rows[bid][0]
        txt = next(w for w in row.winfo_children()
                   if isinstance(w, tk.Text))
        txt.delete("1.0", "end")
        txt.insert("1.0", "EDITED BY SMOKE")
        app.edit_block(bid, txt.get("1.0", "end-1c"))
        grab(app, "edited.png")

        # clear-cache path, skipping the askyesno dialog
        import frontend.tkinter_gui.app as appmod
        appmod.messagebox.askyesno = lambda *a, **k: True
        app._clear_cache()
        pump(app, lambda: not svc._is_processed(9), "cache cleared")
        pump(app, lambda: svc._is_processed(9)
             and all(b.translation for b in svc.get_blocks(9)),
             "page 9 re-processed")
        print("clear ok")

        print("smoke ok")
    finally:
        app._close()


if __name__ == "__main__":
    main()
