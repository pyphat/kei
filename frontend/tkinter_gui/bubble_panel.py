import tkinter as tk
from tkinter import ttk

_JP_FONT = ("Yu Gothic UI", 10)


class BubblePanel(ttk.Frame):
    """Right-hand list of the current page's text blocks in reading order."""

    def __init__(self, master, app):
        super().__init__(master)
        self.app = app
        self.rows = {}
        canvas = tk.Canvas(self, width=280, highlightthickness=0)
        sb = ttk.Scrollbar(self, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=sb.set)
        canvas.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.inner = ttk.Frame(canvas)
        canvas.create_window((0, 0), window=self.inner, anchor="nw",
                             width=260)
        self.inner.bind("<Configure>", lambda e: canvas.configure(
            scrollregion=canvas.bbox("all")))
        canvas.bind("<MouseWheel>", lambda e: canvas.yview_scroll(
            -(e.delta // 120) * 3, "units"))
        self._canvas = canvas

    def set_blocks(self, blocks):
        for w in self.inner.winfo_children():
            w.destroy()
        self.rows = {}
        for n, b in enumerate(blocks):
            fr = tk.Frame(self.inner, bd=1, relief="groove", padx=4, pady=4)
            fr.pack(fill="x", pady=2, padx=2)
            top = tk.Frame(fr)
            top.pack(fill="x")
            tk.Label(top, text=f"#{n}", font=("Segoe UI", 9, "bold")).pack(
                side="left")
            ttk.Button(top, text="✕", width=2,
                       command=lambda bid=b.id: self.app.delete_block(bid)
                       ).pack(side="right")
            src = tk.Label(fr, text=b.source or "(no text)",
                           wraplength=220, justify="left", font=_JP_FONT)
            src.pack(fill="x")
            txt = tk.Text(fr, height=3, wrap="word")
            txt.insert("1.0", b.translation)
            txt.pack(fill="x")
            txt.bind("<FocusOut>",
                     lambda e, b=b, t=txt: self.app.edit_block(
                         b.id, t.get("1.0", "end-1c")))
            txt.bind("<Control-Return>",
                     lambda e, b=b, t=txt: (self.app.edit_block(
                         b.id, t.get("1.0", "end-1c")), "break")[1])
            for w in (fr, top, src, txt):
                w.bind("<Button-1>",
                       lambda e, b=b: self.app.select_block(b.id))
            self.rows[b.id] = (fr, fr.cget("bg"))

    def highlight(self, bid):
        for k, (fr, bg) in self.rows.items():
            fr.configure(bg="#fff3b0" if k == bid else bg)
