import logging
import tkinter as tk
from dataclasses import asdict
from tkinter import filedialog, ttk

log = logging.getLogger(__name__)


def _entry(parent, row, label, value, show=None):
    ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w",
                                       padx=6, pady=3)
    var = tk.StringVar(value=value)
    ttk.Entry(parent, textvariable=var, show=show, width=34).grid(
        row=row, column=1, sticky="ew", padx=6, pady=3)
    return var


class SettingsDialog(tk.Toplevel):
    def __init__(self, app):
        super().__init__(app)
        self.app = app
        s = app.settings
        self.title("Settings")
        self.transient(app)
        self.grab_set()
        body = ttk.Frame(self, padding=8)
        body.pack(fill="both", expand=True)
        body.columnconfigure(1, weight=1)

        r = 0
        self.api_key = _entry(body, r, "OpenRouter API key", s.api_key,
                              show="*"); r += 1
        ttk.Label(body, text="Model priority (one id per line)").grid(
            row=r, column=0, sticky="nw", padx=6, pady=3)
        self.models = tk.Text(body, height=4, width=34)
        self.models.insert("1.0", "\n".join(s.model_priority))
        self.models.grid(row=r, column=1, sticky="ew", padx=6, pady=3); r += 1
        self.lang = _entry(body, r, "Target language", s.target_lang); r += 1

        ttk.Label(body, text="Font file").grid(row=r, column=0, sticky="w",
                                               padx=6, pady=3)
        fr = ttk.Frame(body)
        fr.grid(row=r, column=1, sticky="ew", padx=6, pady=3)
        self.font_path = tk.StringVar(value=s.font_path)
        ttk.Entry(fr, textvariable=self.font_path, width=26).pack(side="left")
        ttk.Button(fr, text="…", width=2, command=lambda: self.font_path.set(
            filedialog.askopenfilename(filetypes=[("Fonts", "*.ttf *.otf")])
            or self.font_path.get())).pack(side="left", padx=4)
        r += 1

        self.font_min = _entry(body, r, "Font min", s.font_min); r += 1
        self.font_max = _entry(body, r, "Font max", s.font_max); r += 1
        self.inpainter = self._combo(body, r, "Inpainter", s.inpainter,
                                     ["auto", "opencv", "lama"]); r += 1
        self.device = self._combo(body, r, "Device", s.device,
                                  ["auto", "cuda", "cpu"]); r += 1

        ttk.Label(body, text="Cache dir").grid(row=r, column=0, sticky="w",
                                               padx=6, pady=3)
        fr2 = ttk.Frame(body)
        fr2.grid(row=r, column=1, sticky="ew", padx=6, pady=3)
        self.cache_dir = tk.StringVar(value=s.cache_dir)
        ttk.Entry(fr2, textvariable=self.cache_dir, width=26).pack(side="left")
        ttk.Button(fr2, text="…", width=2, command=lambda: self.cache_dir.set(
            filedialog.askdirectory() or self.cache_dir.get())).pack(
            side="left", padx=4)
        r += 1

        self.det_conf = _entry(body, r, "Detection confidence",
                               s.det_conf); r += 1
        self.batch = _entry(body, r, "Batch pages", s.batch_pages); r += 1

        btns = ttk.Frame(body)
        btns.grid(row=r, column=0, columnspan=2, pady=8)
        ttk.Button(btns, text="Save", command=self._save).pack(
            side="left", padx=4)
        ttk.Button(btns, text="Cancel", command=self.destroy).pack(
            side="left", padx=4)

    def _combo(self, parent, row, label, value, values):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w",
                                           padx=6, pady=3)
        var = tk.StringVar(value=value)
        ttk.Combobox(parent, textvariable=var, values=values, width=31,
                     state="readonly").grid(row=row, column=1, sticky="ew",
                                            padx=6, pady=3)
        return var

    def _save(self):
        s = self.app.settings
        s.api_key = self.api_key.get().strip()
        s.model_priority = [l.strip() for l in
                            self.models.get("1.0", "end").splitlines()
                            if l.strip()]
        s.target_lang = self.lang.get().strip() or s.target_lang
        s.font_path = self.font_path.get().strip()
        s.font_min = int(self.font_min.get())
        s.font_max = int(self.font_max.get())
        s.inpainter = self.inpainter.get()
        s.device = self.device.get()
        s.cache_dir = self.cache_dir.get().strip()
        s.det_conf = float(self.det_conf.get())
        s.batch_pages = int(self.batch.get())
        s.save()
        log.info("settings saved: %s",
                 {k: v for k, v in asdict(s).items() if k != "api_key"})
        t = self.app.svc.translator
        if hasattr(t, "_free"):
            t._free = None  # drop cached free-model list
        self.app.on_settings_saved()
        self.destroy()
