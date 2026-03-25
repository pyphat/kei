import json
import os
import random
import tkinter as tk
from tkinter import messagebox

import globals

DECK_PATH = os.path.join(globals.DIR_PATH, "flashcard_deck.json")
FREQ_PATH = os.path.join(globals.DIR_PATH, "word_freq.json")

# ── Persistence ──────────────────────────────────────────────────────────────


def load_deck():
    if os.path.exists(DECK_PATH):
        with open(DECK_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return []


def save_deck(deck):
    with open(DECK_PATH, "w", encoding="utf-8") as f:
        json.dump(deck, f, ensure_ascii=False, indent=2)


def add_card(word, pos, definitions):
    """
    Called from imageViewer.py.  Adds a card if the word is not already in the
    deck, then saves.  Returns True if added, False if it was already there.
    """
    print(DECK_PATH)
    deck = load_deck()
    for card in deck:
        if card["word"] == word:
            return False
    deck.append({"word": word, "pos": pos, "defs": definitions, "score": 0})
    save_deck(deck)
    return True


# ── Frequency tracking ────────────────────────────────────────────────────────


def load_freq():
    if os.path.exists(FREQ_PATH):
        with open(FREQ_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def bump_freq(word):
    """Increment lookup count for a word. Returns new count."""
    freq = load_freq()
    freq[word] = freq.get(word, 0) + 1
    with open(FREQ_PATH, "w", encoding="utf-8") as f:
        json.dump(freq, f, ensure_ascii=False, indent=2)
    return freq[word]


# ── Flashcard Window ──────────────────────────────────────────────────────────


class FlashcardWindow(tk.Toplevel):
    """
    A Toplevel flashcard window.  Pass the parent root so it floats above the
    reader without blocking it.
    """

    BG = "#f5f0e8"
    BG2 = "#ede8dc"
    INK = "#1a1612"
    FADED = "#7a6f60"
    RED = "#a02828"
    GREEN = "#2d7a4f"
    AMBER = "#a07820"
    BORDER = "#c8bfaa"

    def __init__(self, parent):
        super().__init__(parent)
        self.title("単語帳 — Flashcards")
        self.configure(bg=self.BG)
        self.geometry("640x560")
        self.resizable(True, True)

        self.deck = []
        self.queue = []
        self.q_idx = 0
        self.flipped = False
        self.session_good = 0
        self.session_bad = 0

        self._build_ui()
        self.reload_deck()

    # ── UI construction ───────────────────────────────────────────────────────

    def _build_ui(self):
        # Top bar
        top = tk.Frame(self, bg=self.BG)
        top.pack(fill="x", padx=24, pady=(20, 0))

        tk.Label(
            top,
            text="単語帳",
            font=("Shippori Antique", 22, "bold"),
            bg=self.BG,
            fg=self.INK,
        ).pack(side="left")

        self.stats_label = tk.Label(
            top,
            text="",
            font=("Shippori Antique", 13),
            bg=self.BG,
            fg=self.FADED,
        )
        self.stats_label.pack(side="right", pady=6)

        tk.Frame(self, height=2, bg=self.INK).pack(fill="x", padx=24, pady=(8, 0))

        # Tab bar
        tab_bar = tk.Frame(self, bg=self.BG)
        tab_bar.pack(fill="x", padx=24, pady=(12, 0))

        self.tab_study = self._tab_btn(tab_bar, "Study", lambda: self._switch("study"))
        self.tab_deck = self._tab_btn(
            tab_bar, "Manage deck", lambda: self._switch("deck")
        )
        self.tab_study.pack(side="left", padx=(0, 6))
        self.tab_deck.pack(side="left")

        # Content frames
        self.study_frame = tk.Frame(self, bg=self.BG)
        self.deck_frame = tk.Frame(self, bg=self.BG)

        self._build_study_frame()
        self._build_deck_frame()

        self._switch("study")

    def _tab_btn(self, parent, text, cmd):
        b = tk.Button(
            parent,
            text=text,
            command=cmd,
            font=("Shippori Antique", 12),
            relief="flat",
            cursor="hand2",
            bg=self.BG,
            fg=self.FADED,
            activebackground=self.BG2,
            activeforeground=self.INK,
            bd=0,
            padx=10,
            pady=4,
        )
        return b

    def _build_study_frame(self):
        f = self.study_frame

        # Progress bar
        pb_bg = tk.Frame(f, bg=self.BORDER, height=3)
        pb_bg.pack(fill="x", padx=24, pady=(16, 4))
        pb_bg.pack_propagate(False)
        self.progress_bar = tk.Frame(pb_bg, bg=self.INK, height=3, width=0)
        self.progress_bar.place(x=0, y=0, relheight=1)

        self.count_label = tk.Label(
            f,
            text="",
            font=("Shippori Antique", 12),
            bg=self.BG,
            fg=self.FADED,
        )
        self.count_label.pack(pady=(0, 8))

        # Card
        card_outer = tk.Frame(f, bg=self.BORDER, bd=0)
        card_outer.pack(padx=40, pady=0, fill="x")

        self.card_frame = tk.Frame(
            card_outer,
            bg="white",
            bd=0,
            highlightthickness=1,
            highlightbackground=self.BORDER,
        )
        self.card_frame.pack(fill="both", padx=1, pady=1)

        self.pos_label = tk.Label(
            self.card_frame,
            text="",
            font=("Shippori Antique", 10),
            bg="white",
            fg=self.FADED,
            anchor="e",
        )
        self.pos_label.pack(fill="x", padx=16, pady=(14, 0))

        self.word_label = tk.Label(
            self.card_frame,
            text="",
            font=("Shippori Antique", 52, "bold"),
            bg="white",
            fg=self.INK,
        )
        self.word_label.pack(pady=(4, 0))

        self.hint_label = tk.Label(
            self.card_frame,
            text="click to reveal",
            font=("Shippori Antique", 12),
            bg="white",
            fg=self.FADED,
        )
        self.hint_label.pack(pady=(0, 20))

        self.def_label = tk.Label(
            self.card_frame,
            text="",
            font=("Shippori Antique", 15),
            bg="white",
            fg=self.FADED,
            wraplength=480,
            justify="center",
        )
        self.def_label.pack(pady=(0, 24))

        for w in (
            self.card_frame,
            self.word_label,
            self.pos_label,
            self.hint_label,
            self.def_label,
        ):
            w.bind("<Button-1>", lambda e: self.flip_card())

        # Rating buttons
        btn_row = tk.Frame(f, bg=self.BG)
        btn_row.pack(padx=40, pady=14, fill="x")

        self.btn_again = self._rate_btn(
            btn_row, "Again ✕", self.RED, lambda: self.rate("bad")
        )
        self.btn_ok = self._rate_btn(
            btn_row, "Okay  ～", self.AMBER, lambda: self.rate("ok")
        )
        self.btn_got = self._rate_btn(
            btn_row, "Got it ✓", self.GREEN, lambda: self.rate("good")
        )

        self.btn_again.pack(side="left", expand=True, fill="x", padx=(0, 6))
        self.btn_ok.pack(side="left", expand=True, fill="x", padx=3)
        self.btn_got.pack(side="left", expand=True, fill="x", padx=(6, 0))

        # Done message (hidden initially)
        self.done_frame = tk.Frame(f, bg=self.BG)

        tk.Label(
            self.done_frame,
            text="Session complete",
            font=("Shippori Antique", 20, "bold"),
            bg=self.BG,
            fg=self.INK,
        ).pack(pady=(30, 6))

        self.done_detail = tk.Label(
            self.done_frame,
            text="",
            font=("Shippori Antique", 13),
            bg=self.BG,
            fg=self.FADED,
        )
        self.done_detail.pack(pady=(0, 20))

        tk.Button(
            self.done_frame,
            text="Study again",
            font=("Shippori Antique", 13),
            relief="flat",
            cursor="hand2",
            bg=self.INK,
            fg="white",
            activebackground="#333",
            padx=20,
            pady=8,
            command=self.start_study,
        ).pack()

    def _rate_btn(self, parent, text, color, cmd):
        return tk.Button(
            parent,
            text=text,
            command=cmd,
            font=("Shippori Antique", 13),
            relief="flat",
            cursor="hand2",
            bg="white",
            fg=color,
            activebackground=self.BG2,
            activeforeground=color,
            highlightthickness=1,
            highlightbackground=self.BORDER,
            pady=10,
        )

    def _build_deck_frame(self):
        f = self.deck_frame

        # Add-card row
        add_row = tk.Frame(f, bg=self.BG)
        add_row.pack(fill="x", padx=24, pady=(16, 8))

        self.inp_word = tk.Entry(
            add_row,
            width=8,
            font=("Shippori Antique", 16),
            relief="flat",
            bg="white",
            fg=self.INK,
            highlightthickness=1,
            highlightbackground=self.BORDER,
        )
        self.inp_word.pack(side="left", padx=(0, 6), ipady=4)

        self.pos_var = tk.StringVar(value="NOUN")
        pos_menu = tk.OptionMenu(
            add_row, self.pos_var, "NOUN", "VERB", "ADJ", "ADV", "PARTICLE", "OTHER"
        )
        pos_menu.config(
            font=("Shippori Antique", 12),
            relief="flat",
            bg="white",
            fg=self.INK,
            activebackground=self.BG2,
            highlightthickness=1,
            highlightbackground=self.BORDER,
        )
        pos_menu.pack(side="left", padx=(0, 6))

        self.inp_def = tk.Entry(
            add_row,
            font=("Shippori Antique", 13),
            relief="flat",
            bg="white",
            fg=self.INK,
            highlightthickness=1,
            highlightbackground=self.BORDER,
        )
        self.inp_def.pack(side="left", fill="x", expand=True, padx=(0, 6), ipady=4)

        tk.Button(
            add_row,
            text="Add",
            command=self._add_card_ui,
            font=("Shippori Antique", 13),
            relief="flat",
            cursor="hand2",
            bg=self.INK,
            fg="white",
            activebackground="#333",
            padx=12,
            pady=4,
        ).pack(side="left")

        # Scrollable list
        list_outer = tk.Frame(f, bg=self.BG)
        list_outer.pack(fill="both", expand=True, padx=24, pady=(0, 16))

        list_canvas = tk.Canvas(list_outer, bg=self.BG, highlightthickness=0)
        scrollbar = tk.Scrollbar(
            list_outer, orient="vertical", command=list_canvas.yview
        )
        list_canvas.configure(yscrollcommand=scrollbar.set)

        scrollbar.pack(side="right", fill="y")
        list_canvas.pack(side="left", fill="both", expand=True)

        self.list_frame = tk.Frame(list_canvas, bg=self.BG)
        win = list_canvas.create_window((0, 0), window=self.list_frame, anchor="nw")

        list_canvas.bind(
            "<Configure>",
            lambda e: list_canvas.itemconfig(win, width=e.width),
        )
        self.list_frame.bind(
            "<Configure>",
            lambda e: list_canvas.configure(scrollregion=list_canvas.bbox("all")),
        )
        list_canvas.bind(
            "<Enter>",
            lambda e: self.bind_all(
                "<MouseWheel>",
                lambda ev: list_canvas.yview_scroll(
                    int(-1 * (ev.delta / 120)), "units"
                ),
            ),
        )
        list_canvas.bind("<Leave>", lambda e: self.unbind_all("<MouseWheel>"))

        self.empty_label = tk.Label(
            self.list_frame,
            text="No cards yet. Add some above.",
            font=("Shippori Antique", 13),
            bg=self.BG,
            fg=self.FADED,
        )

    # ── Tab switching ─────────────────────────────────────────────────────────

    def _switch(self, tab):
        self.study_frame.pack_forget()
        self.deck_frame.pack_forget()

        active = {"bg": self.INK, "fg": "white", "relief": "flat"}
        inactive = {"bg": self.BG, "fg": self.FADED, "relief": "flat"}

        if tab == "study":
            self.tab_study.config(**active)
            self.tab_deck.config(**inactive)
            self.study_frame.pack(fill="both", expand=True)
        else:
            self.tab_study.config(**inactive)
            self.tab_deck.config(**active)
            self.deck_frame.pack(fill="both", expand=True)
            self._render_deck_list()

    # ── Deck helpers ──────────────────────────────────────────────────────────

    def reload_deck(self):
        self.deck = load_deck()
        self._update_stats()
        self.start_study()

    def _update_stats(self):
        self.stats_label.config(text=f"{len(self.deck)} cards")

    def _add_card_ui(self):
        word = self.inp_word.get().strip()
        pos = self.pos_var.get()
        defs = self.inp_def.get().strip()
        if not word or not defs:
            return
        added = add_card(word, pos, [defs])
        if not added:
            messagebox.showinfo("Already in deck", f"{word} is already in your deck.")
            return
        self.deck = load_deck()
        self.inp_word.delete(0, "end")
        self.inp_def.delete(0, "end")
        self._update_stats()
        self._render_deck_list()

    def _delete_card(self, word):
        self.deck = [c for c in self.deck if c["word"] != word]
        save_deck(self.deck)
        self._update_stats()
        self._render_deck_list()

    def _render_deck_list(self):
        for w in self.list_frame.winfo_children():
            w.destroy()

        if not self.deck:
            self.empty_label = tk.Label(
                self.list_frame,
                text="No cards yet.",
                font=("Shippori Antique", 13),
                bg=self.BG,
                fg=self.FADED,
            )
            self.empty_label.pack(pady=20)
            return

        STATUS = {0: "new", 1: "new", 2: "learning", 3: "learning"}

        for card in self.deck:
            status = STATUS.get(card["score"], "learned")

            row = tk.Frame(
                self.list_frame,
                bg="white",
                highlightthickness=1,
                highlightbackground=self.BORDER,
            )
            row.pack(fill="x", pady=3)

            tk.Label(
                row,
                text=card["word"],
                font=("Shippori Antique", 18, "bold"),
                bg="white",
                fg=self.INK,
                width=6,
                anchor="w",
            ).pack(side="left", padx=(12, 0), pady=8)

            tk.Label(
                row,
                text=card["pos"],
                font=("Shippori Antique", 10),
                bg="white",
                fg=self.FADED,
                width=8,
                anchor="w",
            ).pack(side="left", padx=8)

            tk.Label(
                row,
                text="\n ".join(card["defs"]),
                font=("Shippori Antique", 12),
                bg="white",
                fg=self.FADED,
                anchor="w",
            ).pack(side="left", fill="x", expand=True)

            status_colors = {
                "new": self.FADED,
                "learning": self.AMBER,
                "learned": self.GREEN,
            }
            tk.Label(
                row,
                text=status,
                font=("Shippori Antique", 11),
                bg="white",
                fg=status_colors[status],
            ).pack(side="right", padx=8)

            tk.Button(
                row,
                text="✕",
                command=lambda w=card["word"]: self._delete_card(w),
                font=("Shippori Antique", 12),
                relief="flat",
                cursor="hand2",
                bg="white",
                fg=self.RED,
                activebackground=self.BG2,
                bd=0,
                padx=8,
            ).pack(side="right")

    # ── Study logic ───────────────────────────────────────────────────────────

    def start_study(self):
        self.deck = load_deck()
        self.queue = sorted(self.deck, key=lambda c: c["score"])
        random.shuffle(self.queue)  # shuffle cards of equal score
        self.q_idx = 0
        self.flipped = False
        self.session_good = 0
        self.session_bad = 0

        self.done_frame.pack_forget()
        self._show_rating_row(True)

        if not self.queue:
            self._show_done()
            return

        self._show_card()

    def _show_card(self):
        card = self.queue[self.q_idx]

        self.pos_label.config(text=card["pos"].upper())
        self.word_label.config(text=card["word"])
        self.hint_label.config(text="click to reveal")
        self.def_label.config(text="")
        self.flipped = False

        total = len(self.queue)
        self.count_label.config(text=f"{self.q_idx + 1} / {total}")

        # Progress bar width relative to card_frame width
        self.update_idletasks()
        try:
            bar_w = self.progress_bar.master.winfo_width()
            pct = self.q_idx / total
            self.progress_bar.place(x=0, y=0, relheight=1, width=int(bar_w * pct))
        except Exception:
            pass

    def flip_card(self):
        if self.flipped:
            return
        card = self.queue[self.q_idx]
        self.hint_label.config(text="")
        self.def_label.config(text="\n".join(card["defs"]))
        self.flipped = True

    def rate(self, r):
        if not self.flipped:
            self.flip_card()
            return

        card = self.queue[self.q_idx]
        dc = next((c for c in self.deck if c["word"] == card["word"]), None)

        if dc:
            if r == "good":
                dc["score"] += 2
                self.session_good += 1
            elif r == "ok":
                dc["score"] += 1
            else:
                dc["score"] = max(0, dc["score"] - 1)
                self.session_bad += 1
            save_deck(self.deck)

        self.q_idx += 1
        if self.q_idx >= len(self.queue):
            self._show_done()
        else:
            self._show_card()

    def _show_done(self):
        self._show_rating_row(False)
        self.word_label.config(text="")
        self.pos_label.config(text="")
        self.hint_label.config(text="")
        self.def_label.config(text="")
        self.count_label.config(text="")

        detail = f"{self.session_good} known · {self.session_bad} to review"
        self.done_detail.config(text=detail)
        self.done_frame.pack(pady=20)

    def _show_rating_row(self, show):
        for btn in (self.btn_again, self.btn_ok, self.btn_got):
            if show:
                btn.pack(side="left", expand=True, fill="x", padx=3)
            else:
                btn.pack_forget()


# ── Standalone entry point ────────────────────────────────────────────────────


def open_flashcards(parent=None):
    """
    Call this from imageViewer.py to open (or raise) the flashcard window.
    If parent is None, creates its own Tk root (standalone mode).
    """
    if parent is None:
        root = tk.Tk()
        root.withdraw()
        win = FlashcardWindow(root)
        win.protocol("WM_DELETE_WINDOW", root.destroy)
        root.mainloop()
    else:
        win = FlashcardWindow(parent)
        win.lift()
        win.focus_force()
        return win


if __name__ == "__main__":
    open_flashcards()
