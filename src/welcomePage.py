import os
import tkinter as tk
from tkinter import filedialog

from tkextrafont import Font


class WelcomePage:
    def __init__(self, root, on_launch):
        """
        Welcome screen for the manga reader.

        root     — the Tk root window
        on_launch — callback(folder_path: str) called when the user confirms
        """
        self.root = root
        self.on_launch = on_launch
        self.selected_path = tk.StringVar(value="")

        root.title("manga reader")
        root.attributes("-fullscreen", True)
        root.configure(bg="#0d0d0d")

        self._build()

    # ------------------------------------------------------------------ #
    # Layout
    # ------------------------------------------------------------------ #

    def _build(self):
        outer = tk.Frame(self.root, bg="#0d0d0d")
        outer.pack(fill="both", expand=True, padx=48, pady=48)

        # ── title block
        title = tk.Label(
            outer,
            text="慧 - kei",
            font=("Shippori Antique", 60, "bold"),
            fg="#f0ede6",
            bg="#0d0d0d",
            anchor="w",
        )
        title.pack(fill="x")

        subtitle = tk.Label(
            outer,
            text="THE AI-POWERED MANGA READER THAT WILL HELP YOU LEARN",
            font=("Shippori Antique", 12),
            fg="#6b6b6b",
            bg="#0d0d0d",
            anchor="w",
        )
        subtitle.pack(fill="x", pady=(4, 36))

        # ── folder row
        folder_row = tk.Frame(outer, bg="#0d0d0d")
        folder_row.pack(fill="x", pady=(0, 8))

        folder_label = tk.Label(
            folder_row,
            text="SELECT THE FOLDER CONTAINING YOUR MANGA CHAPTERS TO GET STARTED.",
            font=("Shippori Antique", 12),
            fg="#f0ede6",
            bg="#0d0d0d",
            anchor="w",
        )
        folder_label.pack(fill="x", pady=(0, 6))

        entry_frame = tk.Frame(
            folder_row,
            bg="#1a1a1a",
            highlightthickness=1,
            highlightbackground="#2e2e2e",
            highlightcolor="#e8c96b",
        )
        entry_frame.pack(fill="x")

        self.path_entry = tk.Entry(
            entry_frame,
            textvariable=self.selected_path,
            font=("Courier", 16),
            fg="#c8c5be",
            bg="#1a1a1a",
            insertbackground="#e8c96b",
            relief="flat",
            bd=0,
        )
        self.path_entry.pack(side="left", fill="both", expand=True, padx=12, pady=10)

        browse_btn = tk.Button(
            entry_frame,
            text="📁",
            font=("Segoe UI Emoji", 16),
            fg="#0d0d0d",
            bg="#e8c96b",
            activebackground="#d4b45a",
            activeforeground="#0d0d0d",
            relief="flat",
            bd=0,
            cursor="hand2",
            padx=16,
            pady=10,
            command=self._browse,
        )
        browse_btn.pack(side="right")

        # ── hint
        self.hint_label = tk.Label(
            outer,
            text="",
            font=("Courier", 10),
            fg="#6b6b6b",
            bg="#0d0d0d",
            anchor="w",
        )
        self.hint_label.pack(fill="x", pady=(6, 0))

        # ── spacer
        tk.Frame(outer, bg="#0d0d0d", height=24).pack()

        # ── bottom row: accent line + launch button
        bottom = tk.Frame(outer, bg="#0d0d0d")
        bottom.pack(fill="x", side="bottom")

        tk.Frame(bottom, bg="#2e2e2e", height=1).pack(fill="x", pady=(0, 20))

        self.launch_btn = tk.Button(
            bottom,
            text="START READING",
            font=("Shippori Antique", 24),
            fg="#0d0d0d",
            bg="#e8c96b",
            relief="flat",
            bd=0,
            cursor="hand2",
            padx=18,
            pady=14,
            command=self._launch,
        )
        self.launch_btn.pack(side="right")

        # watch path changes
        self.selected_path.trace_add("write", self._on_path_change)

    # ------------------------------------------------------------------ #
    # Interactions
    # ------------------------------------------------------------------ #

    def _browse(self):
        folder = filedialog.askdirectory(title="Select manga chapter folder")
        if folder:
            self.selected_path.set(folder)

    def _on_path_change(self, *_):
        path = self.selected_path.get().strip()
        if path and os.path.isdir(path):
            images = [
                f
                for f in os.listdir(path)
                if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
            ]
            count = len(images)
            if count:
                self.hint_label.config(
                    text=f"✓  {count} image{'s' if count != 1 else ''} found",
                    fg="#7dba84",
                )
                self.launch_btn.config(state="normal")
            else:
                self.hint_label.config(
                    text="⚠  no images found in this folder",
                    fg="#c97b5a",
                )
                self.launch_btn.config(state="disabled")
        elif path:
            self.hint_label.config(text="⚠  path does not exist", fg="#c97b5a")
            self.launch_btn.config(state="disabled")
        else:
            self.hint_label.config(text="")
            self.launch_btn.config(state="disabled")

    def _launch(self):
        path = self.selected_path.get().strip()
        if path and os.path.isdir(path):
            # destroy all welcome widgets, hand control back
            for widget in self.root.winfo_children():
                widget.destroy()
            self.on_launch(path)

    # ------------------------------------------------------------------ #
    # Helpers
    # ------------------------------------------------------------------ #

    def _center_window(self, w, h):
        self.root.update_idletasks()
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        self.root.geometry(f"{w}x{h}+{x}+{y}")


# ------------------------------------------------------------------ #
# Standalone demo
# ------------------------------------------------------------------ #

if __name__ == "__main__":

    def on_launch(folder):
        print("Launching with folder:", folder)
        # Replace this with:  ImageViewer(root, folder)

    root = tk.Tk()
    Font(file="C:/Coding/Python/kei/fonts/Satoshi.ttf", family="Satoshi")
    Font(
        file="C:/Coding/Python/kei/fonts/ShipporiAntique.ttf", family="Shippori Antique"
    )
    WelcomePage(root, on_launch)
    root.mainloop()
