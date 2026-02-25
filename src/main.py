import os
import tkinter as tk

from PIL import Image, ImageTk

from service.ocrService import OcrService
from service.translateService import TranslateService


class ImageViewer:
    def __init__(self, root):
        self.root = root
        self.root.title("manga-01")

        self.image_paths = [os.path.join("test", f"{i:03}.jpg") for i in range(2, 26)]

        self.index = 0
        self.start_x: int = 0
        self.start_y: int = 0
        self.end_x: int = 0
        self.end_y: int = 0
        self.rect = None

        # -------------------
        # Layout Setup
        # -------------------

        main_frame = tk.Frame(root)
        main_frame.pack(fill="both", expand=True)

        # Canvas (left side)
        self.canvas = tk.Canvas(main_frame, cursor="cross")
        self.canvas.pack(side="left")

        # Right panel
        right_panel = tk.Frame(main_frame, width=150, bg="#eeeeee")
        right_panel.pack(side="right", fill="y")

        self.page_label = tk.Label(
            right_panel, text="", font=("Arial", 14), bg="#eeeeee"
        )
        self.page_label.pack(pady=20)

        # Navigation buttons (bottom)
        button_frame = tk.Frame(root)
        button_frame.pack()

        tk.Button(button_frame, text="Previous", command=self.show_prev).pack(
            side=tk.LEFT, padx=5
        )
        tk.Button(button_frame, text="Next", command=self.show_next).pack(
            side=tk.LEFT, padx=5
        )

        # Mouse bindings
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)

        self.load_image()

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

        # Update page label
        total = len(self.image_paths)
        self.page_label.config(text=f"Page:\n{self.index + 1} / {total}")

        self.rect = None
        self.start_x = 0
        self.start_y = 0
        self.end_x = 0
        self.end_y = 0

    # -------------------
    # Drawing logic
    # -------------------

    def on_press(self, event):
        self.start_x = event.x
        self.start_y = event.y
        self.rect = None

    def on_drag(self, event):
        if self.start_x is None or self.start_y is None:
            return

        if self.rect:
            self.canvas.delete(self.rect)

        self.rect = self.canvas.create_rectangle(
            self.start_x, self.start_y, event.x, event.y, outline="red", width=2
        )

        self.end_x = event.x
        self.end_y = event.y

    def on_release(self, event):
        if self.rect:
            self.canvas.delete(self.rect)
            self.rect = None
        ocr_service = OcrService(
            self.start_x,
            self.start_y,
            self.end_x,
            self.end_y,
            self.image_paths[self.index],
            False,
        )
        translate = TranslateService(ocr_service.run(), False)
        self.page_label.config(text=translate.run())
        self.start_x = 0
        self.start_y = 0
        self.end_x = 0
        self.end_y = 0

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
    root.mainloop()
