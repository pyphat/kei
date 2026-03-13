import os
import threading
import tkinter as tk

from fugashi import Tagger
from jamdict import Jamdict
from PIL import Image, ImageTk

import fonts
import globals as g
from objects.mangaPage import MangaPage
from welcomePage import WelcomePage

# -------------------
# NLP Initialization
# -------------------

print("[DEBUG] Initializing MeCab (fugashi)")
tagger = Tagger()

print("[DEBUG] Loading JMdict dictionary")
jam = Jamdict()


class ImageViewer:
    def __init__(self, root, folder):

        self.root = root
        self.root.title("manga-01")
        self.root.attributes("-fullscreen", True)
        self.root.bind("<Escape>", lambda e: self.root.attributes("-fullscreen", False))
        self.root.bind("<F11>", lambda e: self.root.attributes("-fullscreen", True))

        # Build image list from the selected folder
        image_exts = (".jpg", ".jpeg", ".png", ".webp")
        all_files = sorted(os.listdir(folder))
        self.image_paths = [
            os.path.join(folder, f) for f in all_files if f.lower().endswith(image_exts)
        ]

        self.index = 0
        self.page = None
        self.image = None
        self.image_scale = 1.0

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
        self.canvas.pack(side="left", fill="y")
        self.canvas.bind("<Configure>", self._on_canvas_resize)

        right_panel = tk.Frame(main_frame, bg="#eeeeee")
        right_panel.pack(side="left", fill="both", expand=True)

        self.page_label = tk.Label(
            right_panel, text="", font=("Shippori Antique", 28), bg="#eeeeee"
        )

        self.ocr_text = tk.Label(
            right_panel,
            text="",
            font=("Shippori Antique", 18),
            bg="#eeeeee",
            wraplength=0,
            justify="left",
        )

        right_panel.bind(
            "<Configure>",
            lambda e: self.ocr_text.config(wraplength=e.width - 20),
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

        button_frame = tk.Frame(right_panel, bg="#eeeeee")

        tk.Button(button_frame, text="⌂ Home", command=self.go_home).pack(
            side=tk.LEFT, padx=5
        )

        tk.Button(button_frame, text="Previous", command=self.show_prev).pack(
            side=tk.LEFT, padx=5
        )

        tk.Button(button_frame, text="Next", command=self.show_next).pack(
            side=tk.LEFT, padx=5
        )

        self.page_label.pack(pady=20)

        self.ocr_text.pack(pady=10)
        self.words.pack(pady=10, fill="both", expand=True)

        # Bottom container
        bottom_bar = tk.Frame(right_panel, bg="#eeeeee")
        bottom_bar.pack(side="bottom", fill="x", pady=10)

        # push buttons to the right
        button_frame = tk.Frame(bottom_bar, bg="#eeeeee")
        button_frame.pack(side="right", padx=10)

        tk.Button(button_frame, text="⌂ Home", command=self.go_home).pack(
            side=tk.LEFT, padx=5
        )

        tk.Button(button_frame, text="Previous", command=self.show_prev).pack(
            side=tk.LEFT, padx=5
        )

        tk.Button(button_frame, text="Next", command=self.show_next).pack(
            side=tk.LEFT, padx=5
        )

        # mouse events
        self.canvas.bind("<Motion>", self.on_hover)
        self.canvas.bind("<Button-1>", self.on_click)
        self.root.bind("<Left>", lambda e: self.show_prev())
        self.root.bind("<Right>", lambda e: self.show_next())

        self.load_image()

        # start background prefetch
        self.start_prefetch()

    # -------------------
    # Load Image
    # -------------------

    def load_image(self):

        path = self.image_paths[self.index]

        self.image = Image.open(path)
        self._render_image()

        total = len(self.image_paths)

        self.page_label.config(text=f"Loading...\n{self.index + 1} / {total}")

        self.hover_rect = None
        self.hovered_bubble = None

        if path in self.page_cache:
            self.page = self.page_cache[path]

            print("Loaded from cache:", path)

            self.page_label.config(text=f"Page: {self.index + 1} / {total}")

        else:
            threading.Thread(
                target=self._load_page_background,
                args=(path,),
                daemon=True,
            ).start()

    def _render_image(self):
        if not self.image:
            return

        canvas_h = self.canvas.winfo_height()
        if canvas_h < 2:
            canvas_h = self.root.winfo_screenheight()

        orig_w, orig_h = self.image.size
        scale = canvas_h / orig_h
        new_w = int(orig_w * scale)
        new_h = canvas_h

        resized = self.image.resize((new_w, new_h), Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(resized)

        self.canvas.config(width=new_w, height=new_h)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor="nw", image=self.photo)

        # store scale for bubble coordinate mapping
        self.image_scale = scale

    def _on_canvas_resize(self, event):
        if self.image:
            self._render_image()

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

        scale = getattr(self, "image_scale", 1.0)
        bubble = self.page.find_bubble(int(event.x / scale), int(event.y / scale))

        if bubble == self.hovered_bubble:
            return

        if self.hover_rect:
            self.canvas.delete(self.hover_rect)
            self.hover_rect = None

        self.hovered_bubble = bubble

        if bubble:
            det = bubble["bbox"]
            scale = getattr(self, "image_scale", 1.0)

            self.hover_rect = self.canvas.create_rectangle(
                det["x1"] * scale,
                det["y1"] * scale,
                det["x2"] * scale,
                det["y2"] * scale,
                outline="green",
                width=2,
            )

    # -------------------
    # Click translate
    # -------------------

    def on_click(self, event):

        if not self.page:
            return

        scale = getattr(self, "image_scale", 1.0)
        bubble = self.page.find_bubble(int(event.x / scale), int(event.y / scale))

        if not bubble:
            return

        text = bubble["text"]
        self.ocr_text.config(text=text)

        self.words.delete("1.0", tk.END)

        for word in tagger(text):
            surface = word.surface
            lemma = word.feature.lemma
            pos = word.feature.pos1
            if lemma == "*" or not lemma:
                lemma = surface

            result = jam.lookup(lemma)

            self.words.insert(tk.END, surface + "\n", "surface")

            if not result.entries:
                self.words.insert(tk.END, "  (no definition)\n", "definition")
                continue

            for entry in result.entries[:2]:
                for sense in entry.senses[:1]:
                    gloss = ", ".join(g.text for g in sense.gloss)
                    self.words.insert(
                        tk.END, f"{sense.pos[0]}  {gloss}\n", "definition"
                    )

            self.words.insert(tk.END, "\n")

    # -------------------
    # Home
    # -------------------

    def go_home(self):
        for widget in self.root.winfo_children():
            widget.destroy()
        self.root.unbind("<Escape>")
        self.root.unbind("<F11>")
        WelcomePage(self.root, launch_reader)

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


# -------------------
# Entry point
# -------------------


def launch_reader(folder):
    app = ImageViewer(root, folder)
    root.after(100, g.warm_up)


if __name__ == "__main__":
    print("[DEBUG] Started the program.")
    root = tk.Tk()
    fonts.load_font()
    WelcomePage(root, launch_reader)

    root.mainloop()
