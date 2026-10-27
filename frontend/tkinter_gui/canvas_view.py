import tkinter as tk

_GAP = 6


class PageCanvas(tk.Canvas):
    """Center page view. mode single = one fit-height page; continuous =
    vertical stack of all pages at a common (zoomed) width."""

    def __init__(self, master, app):
        super().__init__(master, bg="#222", highlightthickness=0)
        self.app = app
        self._photos = {}       # page -> PhotoImage currently on canvas
        self._offs, self._hs, self._sc = [], [], []
        self._scale = 1.0
        self._drag = None
        self._temp = None
        self._render_after = None
        self.bind("<Configure>", lambda e: self._deferred())
        self.bind("<Button-1>", self._down)
        self.bind("<B1-Motion>", self._move)
        self.bind("<ButtonRelease-1>", self._up)
        self.bind("<MouseWheel>", self._wheel)
        self.bind("<Control-MouseWheel>", self._zwheel)

    # --- rendering ---

    def _deferred(self):
        if self._render_after:
            self.after_cancel(self._render_after)
        self._render_after = self.after_idle(self.render)

    def render(self):
        self._render_after = None
        if not self.app.page_sizes:
            self.delete("all")
            return
        if self.app.mode.get() == "single":
            self._render_single()
        else:
            self._render_cont()

    def _render_single(self):
        i = self.app.page
        w, h = self.app.page_sizes[i]
        vh = max(self.winfo_height(), 50)
        self._scale = vh / h * self.app.zoom
        dw, dh = int(w * self._scale), int(h * self._scale)
        self.delete("all")
        self._photos = {i: self.app.get_photo(i, dw, dh)}
        self.create_image(0, 0, anchor="nw", image=self._photos[i])
        self.config(scrollregion=(0, 0, dw, dh))
        self._draw_boxes(i, self._scale, 0)

    def _render_cont(self):
        cw = max(self.winfo_width() - 20, 100)
        tw = int(cw * self.app.zoom)
        self._offs, self._hs, self._sc = [], [], []
        y = 0
        for w, h in self.app.page_sizes:
            dh = int(h * tw / w)
            self._offs.append(y)
            self._hs.append(dh)
            self._sc.append(tw / w)
            y += dh + _GAP
        self.delete("all")
        for i in range(len(self._offs)):
            self.create_rectangle(0, self._offs[i], tw,
                                  self._offs[i] + self._hs[i], fill="#333",
                                  outline="")
        self.config(scrollregion=(0, 0, tw, y))
        y0, y1 = self.canvasy(0), self.canvasy(self.winfo_height())
        margin = max(self._hs, default=0)
        self._photos = {}
        for i in range(len(self._offs)):
            if self._offs[i] + self._hs[i] < y0 - margin or \
                    self._offs[i] > y1 + margin:
                continue
            self._photos[i] = self.app.get_photo(i, tw, self._hs[i])
            self.create_image(0, self._offs[i], anchor="nw",
                              image=self._photos[i])
            self._draw_boxes(i, self._sc[i], self._offs[i])
        mid = (y0 + y1) / 2
        cur = max((i for i, o in enumerate(self._offs) if o <= mid),
                  default=0)
        self.app.on_canvas_page(cur)

    def _draw_boxes(self, i, scale, yoff):
        if not self.app.show_boxes.get():
            return
        for n, (x1, y1, x2, y2) in enumerate(self.app.svc.get_frames(i)):
            self.create_rectangle(x1 * scale, yoff + y1 * scale,
                                  x2 * scale, yoff + y2 * scale,
                                  outline="#3b82f6", width=2)
        for n, b in enumerate(self.app.svc.get_blocks(i)):
            x1, y1, x2, y2 = b.bbox
            sel = b.id == self.app.selected
            self.create_rectangle(x1 * scale, yoff + y1 * scale,
                                  x2 * scale, yoff + y2 * scale,
                                  outline="#ffd60a" if sel else "#e5484d",
                                  width=3 if sel else 2)
            self.create_text(x1 * scale, yoff + y1 * scale - 8,
                             text=str(n), fill="#e5484d", anchor="w",
                             font=("Segoe UI", 9, "bold"))

    # --- coords ---

    def _to_page(self, cx, cy):
        """canvas coords -> (page_index, page_x, page_y) or None."""
        if self.app.mode.get() == "single":
            return self.app.page, cx / self._scale, cy / self._scale
        for i, (o, h) in enumerate(zip(self._offs, self._hs)):
            if o <= cy < o + h:
                s = self._sc[i]
                return i, cx / s, (cy - o) / s
        return None

    def page_top(self, i):
        return self._offs[i] if self._offs else 0

    def total_height(self):
        return (self._offs[-1] + self._hs[-1]) if self._offs else 1

    # --- mouse ---

    def _down(self, e):
        self._drag = (self.canvasx(e.x), self.canvasy(e.y))

    def _move(self, e):
        if not (self.app.add_mode.get() and self._drag):
            return
        if self._temp:
            self.delete(self._temp)
        x2, y2 = self.canvasx(e.x), self.canvasy(e.y)
        self._temp = self.create_rectangle(*self._drag, x2, y2,
                                           outline="#ffd60a", dash=(4, 2))

    def _up(self, e):
        drag, self._drag = self._drag, None
        if self._temp:
            self.delete(self._temp)
            self._temp = None
        app = self.app
        if app.add_mode.get() and drag:
            a = self._to_page(*drag)
            b = self._to_page(self.canvasx(e.x), self.canvasy(e.y))
            if a and b and a[0] == b[0]:
                x1, x2 = sorted((a[1], b[1]))
                y1, y2 = sorted((a[2], b[2]))
                if x2 - x1 > 5 and y2 - y1 > 5:
                    app.add_box(a[0], (int(x1), int(y1), int(x2), int(y2)))
            return
        if app.show_boxes.get():
            hit = self._to_page(self.canvasx(e.x), self.canvasy(e.y))
            if not hit:
                return
            i, px, py = hit
            for blk in app.svc.get_blocks(i):
                x1, y1, x2, y2 = blk.bbox
                if x1 <= px <= x2 and y1 <= py <= y2:
                    app.select_block(blk.id)
                    return

    def _wheel(self, e):
        self.yview_scroll(-(e.delta // 120) * 3, "units")
        if self.app.mode.get() == "continuous":
            self._deferred()

    def _zwheel(self, e):
        self.app.set_zoom(self.app.zoom * (1.15 if e.delta > 0 else 1 / 1.15))
        return "break"
