import os
import threading
import tkinter as tk
from functools import lru_cache

from fugashi import Tagger
from jamdict import Jamdict
from PIL import Image, ImageTk

import globals as g
from objects.mangaPage import MangaPage

# -------------------
# NLP Initialization
# -------------------

print("[DEBUG] Initializing MeCab (fugashi)")
tagger = Tagger()

print("[DEBUG] Loading JMdict dictionary")
jam = Jamdict()


class ImageViewer:
    def __init__(self, root):

        self.root = root
        self.root.title("manga-01")

        self.image_paths = [os.path.join("test", f"{i:03}.jpg") for i in range(2, 26)]

        self.index = 0
        self.page = None

        # cache
        self.page_cache = {}

        # background prefetch thread
        self.prefetch_thread = None

        self.hover_rect = None
        self.hovered_bubble = None

        # -------------------
        # Layout
        # -------------------

        main_frame = tk.Frame(root)
        main_frame.pack(fill="both", expand=True)

        self.canvas = tk.Canvas(main_frame, cursor="cross")
        self.canvas.pack(side="left")

        right_panel = tk.Frame(main_frame, width=1000, bg="#eeeeee")
        right_panel.pack(side="right", fill="y")

        self.page_label = tk.Label(
            right_panel, text="", font=("Arial", 28), bg="#eeeeee"
        )

        self.ocr_text = tk.Label(
            right_panel,
            text="",
            font=("Arial", 18),
            bg="#eeeeee",
            wraplength=960,
            justify="left",
        )

        self.words = tk.Text(
            right_panel,
            font=("Arial", 14),
            bg="#eeeeee",
            wrap="word",
            borderwidth=0,
        )

        self.words.tag_config("surface", font=("Arial", 22, "bold"))
        self.words.tag_config("definition", font=("Arial", 14))

        self.page_label.pack(pady=20)
        self.ocr_text.pack(pady=10)
        self.words.pack(pady=10, fill="both", expand=True)

        button_frame = tk.Frame(root)
        button_frame.pack()

        tk.Button(button_frame, text="Previous", command=self.show_prev).pack(
            side=tk.LEFT, padx=5
        )

        tk.Button(button_frame, text="Next", command=self.show_next).pack(
            side=tk.LEFT, padx=5
        )

        # mouse events
        self.canvas.bind("<Motion>", self.on_hover)
        self.canvas.bind("<Button-1>", self.on_click)

        self.load_image()

        # start background prefetch
        self.start_prefetch()

    # -------------------
    # Load Image
    # -------------------

    def load_image(self):

        path = self.image_paths[self.index]

        self.image = Image.open(path)
        self.photo = ImageTk.PhotoImage(self.image)

        self.canvas.config(width=self.photo.width(), height=self.photo.height())
        self.canvas.delete("all")

        self.canvas.create_image(0, 0, anchor="nw", image=self.photo)

        total = len(self.image_paths)

        self.page_label.config(text=f"Loading...\n{self.index + 1} / {total}")

        self.hover_rect = None
        self.hovered_bubble = None

        if path in self.page_cache:
            self.page = self.page_cache[path]

            print("Loaded from cache:", path)

            self.page_label.config(text=f"Page:\n{self.index + 1} / {total}")

        else:
            threading.Thread(
                target=self._load_page_background,
                args=(path,),
                daemon=True,
            ).start()

    # -------------------
    # Background page load
    # -------------------

    def _load_page_background(self, path):

        page = MangaPage(path)

        self.page_cache[path] = page

        print("Detected bubbles:", len(page.get_bubbles()), "for", path)

        if self.image_paths[self.index] == path:
            self.root.after(0, self._finish_page_load, page)

    def _finish_page_load(self, page):

        self.page = page

        total = len(self.image_paths)

        self.page_label.config(text=f"Page:\n{self.index + 1} / {total}")

    # -------------------
    # Prefetch entire chapter
    # -------------------

    def start_prefetch(self):

        if self.prefetch_thread:
            return

        self.prefetch_thread = threading.Thread(
            target=self._prefetch_all_pages,
            daemon=True,
        )

        self.prefetch_thread.start()

    def _prefetch_all_pages(self):

        print("Starting full chapter prefetch...")

        for path in self.image_paths:
            if path in self.page_cache:
                continue

            print("Prefetching:", path)

            page = MangaPage(path)

            self.page_cache[path] = page

        print("Prefetch complete.")

    # -------------------
    # Hover highlight
    # -------------------

    def on_hover(self, event):

        if not self.page:
            return

        bubble = self.page.find_bubble(event.x, event.y)

        if bubble == self.hovered_bubble:
            return

        if self.hover_rect:
            self.canvas.delete(self.hover_rect)
            self.hover_rect = None

        self.hovered_bubble = bubble

        if bubble:
            det = bubble["bbox"]

            self.hover_rect = self.canvas.create_rectangle(
                det["x1"],
                det["y1"],
                det["x2"],
                det["y2"],
                outline="green",
                width=2,
            )

    # -------------------
    # Click translate
    # -------------------

    def on_click(self, event):

        if not self.page:
            return

        bubble = self.page.find_bubble(event.x, event.y)

        if not bubble:
            return

        text = bubble["text"]
        self.ocr_text.config(text=text)

        self.words.delete("1.0", tk.END)

        for word in tagger(text):
            surface = word.surface
            lemma = word.feature.lemma

            definition = jam.lookup(lemma)

            # BIG WORD
            self.words.insert(tk.END, surface + "\n", "surface")

            # DEFINITIONS
            for entry in definition.entries:
                self.words.insert(tk.END, str(entry) + "\n", "definition")

            self.words.insert(tk.END, "\n")

    # -------------------
    # Navigation
    # -------------------

    def show_next(self):

        if self.index < len(self.image_paths) - 1:
            self.index += 1
            self.load_image()

    def show_prev(self):

        if self.index > 0:
            self.index -= 1
            self.load_image()


if __name__ == "__main__":
    print("[DEBUG] Started the program.")

    root = tk.Tk()

    app = ImageViewer(root)

    root.after(100, g.warm_up)
    root.after(500, app.load_image)

    root.mainloop()
