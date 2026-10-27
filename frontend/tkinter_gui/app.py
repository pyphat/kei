import os
import queue
import tkinter as tk
from collections import OrderedDict
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from PIL import Image, ImageTk

from kei_core.config import LOG_DIR, LOG_PATH, Settings
from kei_core.service import ReaderService

from .bubble_panel import BubblePanel
from .canvas_view import PageCanvas
from .settings_dialog import SettingsDialog
from .worker import Worker


class ReaderApp(tk.Tk):
    def __init__(self, service=None):
        super().__init__()
        self.title("kei — manga reader (Tkinter)")
        self.geometry("1400x900")
        self.svc = service or ReaderService(Settings.load())
        self.settings = self.svc.s
        self.q = queue.Queue()
        self.out = queue.Queue()
        self.worker = Worker(self.svc, self.q, self.out)
        self.worker.start()

        self.page = 0
        self.page_sizes = []
        self.selected = None
        self.zoom = 1.0
        self._photos = OrderedDict()
        self._stage = ""
        self._usage = 0
        self._shown_nokey = False
        self._shown_terr = False

        self.view = tk.StringVar(value="translated")
        self.mode = tk.StringVar(value="single")
        self.show_boxes = tk.BooleanVar(value=True)
        self.add_mode = tk.BooleanVar(value=False)
        self.rtl = tk.BooleanVar(value=self.settings.reading_rtl)
        self.auto_tr = tk.BooleanVar(value=False)
        self.lang_var = tk.StringVar(value=self.settings.target_lang)
        self.model_var = tk.StringVar()

        self._build_toolbar()
        body = ttk.Frame(self)
        body.pack(fill="both", expand=True)
        self.canvas = PageCanvas(body, self)
        vsb = ttk.Scrollbar(body, orient="vertical",
                            command=self.canvas.yview)
        hsb = ttk.Scrollbar(self, orient="horizontal",
                            command=self.canvas.xview)
        self.canvas.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.panel = BubblePanel(body, self)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        self.panel.grid(row=0, column=2, sticky="ns")
        body.rowconfigure(0, weight=1)
        body.columnconfigure(0, weight=1)
        hsb.pack(fill="x")
        self.status = ttk.Label(self, anchor="w")
        self.status.pack(fill="x")

        for key in ("<Left>", "<Right>", "<a>", "<d>", "<A>", "<D>"):
            self.bind(key, self._key)
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.after(50, self._poll)
        self._status()

    # --- toolbar ---

    def _build_toolbar(self):
        t1 = ttk.Frame(self)
        t1.pack(fill="x")
        ttk.Button(t1, text="Open folder",
                   command=lambda: self._open(filedialog.askdirectory())
                   ).pack(side="left")
        ttk.Button(t1, text="Open .cbz",
                   command=lambda: self._open(filedialog.askopenfilename(
                       filetypes=[("Comics", "*.cbz *.zip")]))
                   ).pack(side="left")
        ttk.Button(t1, text="Export", command=self._export).pack(side="left")
        ttk.Label(t1, text=" Lang:").pack(side="left")
        lang = ttk.Entry(t1, textvariable=self.lang_var, width=10)
        lang.pack(side="left")
        lang.bind("<FocusOut>", lambda e: self._save_lang())
        lang.bind("<Return>", lambda e: self._save_lang())
        ttk.Label(t1, text=" Model:").pack(side="left")
        self.model_box = ttk.Combobox(t1, textvariable=self.model_var,
                                      width=26)
        self.model_box.pack(side="left")
        self.model_box.bind("<<ComboboxSelected>>", self._pick_model)
        ttk.Checkbutton(t1, text="Translate", variable=self.auto_tr,
                        command=self._toggle_translate).pack(side="left")
        for v in ("original", "clean", "translated"):
            ttk.Radiobutton(t1, text=v.capitalize(), value=v,
                            variable=self.view,
                            command=self.canvas_render).pack(side="left")

        t2 = ttk.Frame(self)
        t2.pack(fill="x")
        for v, label in (("single", "Single"), ("continuous", "Continuous")):
            ttk.Radiobutton(t2, text=label, value=v, variable=self.mode,
                            command=self._mode_changed).pack(side="left")
        ttk.Checkbutton(t2, text="RTL", variable=self.rtl,
                        command=self._save_rtl).pack(side="left")
        ttk.Checkbutton(t2, text="Show boxes", variable=self.show_boxes,
                        command=self.canvas_render).pack(side="left")
        ttk.Checkbutton(t2, text="Add box", variable=self.add_mode).pack(
            side="left")
        ttk.Button(t2, text="−", width=2,
                   command=lambda: self.set_zoom(self.zoom / 1.25)
                   ).pack(side="left")
        ttk.Button(t2, text="+", width=2,
                   command=lambda: self.set_zoom(self.zoom * 1.25)
                   ).pack(side="left")
        ttk.Button(t2, text="Fit", width=3,
                   command=lambda: self.set_zoom(1.0)).pack(side="left")
        ttk.Button(t2, text="Reprocess page", command=self._reprocess).pack(
            side="left")
        ttk.Button(t2, text="Clear cache", command=self._clear_cache).pack(
            side="left")
        ttk.Button(t2, text="Settings",
                   command=lambda: SettingsDialog(self)).pack(side="left")
        ttk.Button(t2, text="Log", command=self._open_log).pack(side="left")

    def canvas_render(self):
        self.canvas.render()

    # --- chapter / navigation ---

    def _open(self, path):
        if path:
            self.q.put(("open", path))
            self._stage = "opening…"
            self._status()

    def open_path(self, path):
        self._open(str(path))

    def _on_opened(self, n):
        self.page_sizes = [Image.open(p).size for p in self.svc.pages]
        self.page = 0
        self.selected = None
        self._photos.clear()
        self.q.put(("goto", 0))
        self.q.put(("models",))
        self.canvas.render()
        self._refresh_panel()
        self._status()

    def goto(self, i):
        if not self.page_sizes:
            return
        self.page = max(0, min(i, len(self.page_sizes) - 1))
        self.q.put(("goto", self.page))
        if self.mode.get() == "continuous":
            self.canvas.yview_moveto(
                self.canvas.page_top(self.page) / self.canvas.total_height())
        self.canvas.render()
        self._refresh_panel()
        self._status()

    def on_canvas_page(self, i):
        """Continuous scroll moved the center page."""
        if i == self.page or not self.page_sizes:
            return
        self.page = i
        self.q.put(("goto", i))
        self._refresh_panel()
        self._status()

    def _mode_changed(self):
        if self.mode.get() == "continuous":
            self.canvas.render()
            self.canvas.yview_moveto(
                self.canvas.page_top(self.page) / self.canvas.total_height())
            self.canvas.render()
        else:
            self.canvas.render()

    def _key(self, e):
        if isinstance(self.focus_get(), (tk.Text, tk.Entry, ttk.Entry,
                                         ttk.Combobox)):
            return
        if e.keysym in ("d", "D"):
            nxt = True
        elif e.keysym in ("a", "A"):
            nxt = False
        elif e.keysym == "Left":
            nxt = self.rtl.get()  # RTL: Left = next page
        else:
            nxt = not self.rtl.get()
        self.goto(self.page + (1 if nxt else -1))

    def set_zoom(self, z):
        self.zoom = max(0.1, min(z, 8.0))
        self.canvas.render()

    # --- images ---

    def get_photo(self, i, dw, dh):
        key = (i, self.view.get(), dw, dh)
        if key not in self._photos:
            img = self.svc.get_page(i, self.view.get())
            if img.size != (dw, dh):
                img = img.resize((dw, dh))
            self._photos[key] = ImageTk.PhotoImage(img)
            while len(self._photos) > 12:
                self._photos.popitem(last=False)
        return self._photos[key]

    def _invalidate(self, i):
        for k in [k for k in self._photos if k[0] == i]:
            del self._photos[k]

    # --- blocks / edits ---

    def _refresh_panel(self):
        self.panel.set_blocks(self.svc.get_blocks(self.page)
                              if self.page_sizes else [])
        if self.selected:
            self.panel.highlight(self.selected)

    def select_block(self, bid):
        self.selected = bid
        self.panel.highlight(bid)
        self.canvas.render()

    def edit_block(self, bid, text):
        self.svc.update_translation(self.page, bid, text)
        self._invalidate(self.page)
        self.canvas.render()

    def delete_block(self, bid):
        self.q.put(("delete_block", self.page, bid))

    def add_box(self, i, bbox):
        self.q.put(("add_block", i, bbox))

    def _reprocess(self):
        self.q.put(("reset", self.page))
        self.q.put(("goto", self.page))

    def _clear_cache(self):
        if not self.page_sizes:
            return
        if messagebox.askyesno(
                "Clear cache",
                "Clear cached pages and translations for this chapter?\n\n"
                "Pages will be re-processed and translations re-requested "
                "from OpenRouter (uses free-tier requests). Edited "
                "translations will be lost."):
            self.q.put(("clear_cache",))

    # --- toolbar actions ---

    def _save_lang(self):
        self.settings.target_lang = self.lang_var.get().strip() or "English"
        self.settings.save()

    def _save_rtl(self):
        self.settings.reading_rtl = self.rtl.get()
        self.settings.save()

    def _pick_model(self, _e):
        mid = self.model_var.get()
        self.settings.model_priority = [mid] + [
            m for m in self.settings.model_priority if m != mid]
        self.settings.save()

    def _toggle_translate(self):
        self.q.put(("auto", self.auto_tr.get()))

    def _open_log(self):
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(LOG_PATH if LOG_PATH.exists() else LOG_DIR)

    def on_settings_saved(self):
        self.q.put(("models",))
        self.canvas.render()

    def _export(self):
        if not self.page_sizes:
            return
        if messagebox.askyesno("Export", "Export as .cbz?\n(No = PNG folder)"):
            path = filedialog.asksaveasfilename(
                defaultextension=".cbz",
                filetypes=[("Comic archive", "*.cbz")])
            if path:
                self.q.put(("export", path, "cbz"))
        else:
            path = filedialog.askdirectory()
            if path:
                self.q.put(("export", path, "png"))

    # --- worker messages ---

    def _poll(self):
        try:
            while True:
                self._on_msg(self.out.get_nowait())
        except queue.Empty:
            pass
        self.after(50, self._poll)

    def _on_msg(self, msg):
        kind = msg[0]
        if kind == "opened":
            self._on_opened(msg[1])
        elif kind == "progress":
            self._stage = f"page {msg[1] + 1}: {msg[2]}"
            self._status()
        elif kind == "page_ready":
            self._invalidate(msg[1])
            self._stage = ""
            if msg[1] == self.page:
                self._refresh_panel()
            self.canvas.render()
            self._status()
        elif kind == "translated":
            for i in msg[1]:
                self._invalidate(i)
            if self.page in msg[1]:
                self._refresh_panel()
            self.canvas.render()
        elif kind == "models":
            self.model_box["values"] = [mid for mid, _ in msg[1]]
        elif kind == "usage":
            self._usage = msg[1]
            self._status()
        elif kind == "no_key":
            self.auto_tr.set(False)
            if not self._shown_nokey:
                self._shown_nokey = True
                messagebox.showwarning(
                    "kei", "No OpenRouter API key — set one in Settings.")
        elif kind == "translate_error":
            self._stage = "translate failed"
            self._status()
            if not self._shown_terr:
                self._shown_terr = True
                messagebox.showerror(
                    "kei", f"{msg[1]}\n\nDetails in log: {LOG_PATH}")
        elif kind == "cleared":
            self._photos.clear()
            self._refresh_panel()
            self.canvas.render()
            self._stage = "cache cleared"
            self._status()
            self.q.put(("goto", self.page))
        elif kind == "done":
            self._stage = f"{msg[1]} done"
            self._status()
        elif kind == "error":
            self._stage = "error"
            self._status()
            messagebox.showerror(
                "kei", f"{msg[1]}\n\nDetails in log: {LOG_PATH}")

    def _status(self):
        n = len(self.page_sizes)
        txt = (f"Page {self.page + 1} / {n}" if n else "No chapter")
        if self._stage:
            txt += f"   |   {self._stage}"
        txt += (f"   |   {self.svc.device}"
                f"   |   OpenRouter requests today: {self._usage}")
        self.status.config(text=txt)

    def _close(self):
        self.q.put(("stop",))
        self.worker.join(timeout=3)
        self.destroy()
