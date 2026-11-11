import re

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

_PAD = 4
# shortcut: no kinsoku (JP line-start punctuation rules), add if users complain
_TOKEN = re.compile(r"[\u3000-\u9fff\uac00-\ud7af\uff00-\uffef]|[^\s\u3000-\u9fff\uac00-\ud7af\uff00-\uffef]+|\s+")
_THAI = re.compile(r"[\u0e00-\u0e7f]")


def _tokens(para):
    if not _THAI.search(para):
        return _TOKEN.findall(para)
    from pythainlp.tokenize import word_tokenize  # lazy: only pay the import for Thai text
    return [t for w in word_tokenize(para, engine="newmm", keep_whitespace=True)
            for t in ([w] if _THAI.search(w) else _TOKEN.findall(w))]


def _font(path, size):
    try:
        return ImageFont.truetype(path, size) if path else ImageFont.load_default(size)
    except Exception:
        return ImageFont.load_default(size)


def _wrap(draw, text, font, max_w):
    lines = []
    for para in text.split("\n"):
        line = ""
        for tok in _tokens(para.strip()):
            cand = line + tok
            if not line.strip() or draw.textlength(cand, font=font) <= max_w:
                line = cand
            else:
                lines.append(line.rstrip())
                line = tok.lstrip()
        lines.append(line.rstrip())
    return lines


def _fits(draw, lines, font, w, h):
    widest = max((draw.textlength(l, font=font) for l in lines), default=0)
    box = font.getbbox("Ag")
    lh = (box[3] - box[1]) * 1.25
    return widest <= w and lh * len(lines) <= h, lh


def _fit(draw, text, box, font_path, fmin, fmax):
    """Largest size in [fmin, fmax] whose wrapped lines fit the padded box.
    Returns (size, lines, lh); falls back to fmin if nothing fits."""
    x1, y1, x2, y2 = box
    w, h = x2 - x1 - 2 * _PAD, y2 - y1 - 2 * _PAD
    best, best_lines, best_lh = fmin, None, None
    lo, hi = fmin, fmax
    while lo <= hi:
        size = (lo + hi) // 2
        f = _font(font_path, size)
        lines = _wrap(draw, text, f, w)
        ok, lh = _fits(draw, lines, f, w, h)
        if ok:
            best, best_lines, best_lh = size, lines, lh
            lo = size + 1
        else:
            hi = size - 1
    if best_lines is None:  # doesn't fit even at min: draw at min anyway
        f = _font(font_path, fmin)
        best_lines = _wrap(draw, text, f, w)
        best_lh = _fits(draw, best_lines, f, w, h)[1]
    return best, best_lines, best_lh


def _sizes(fits):
    """Per-block draw sizes: cap each at the page median so a big bubble
    doesn't render its neighbours tiny."""
    if not fits:
        return []
    med = int(np.median(fits))
    return [min(f, med) for f in fits]


def _draw_block(draw, text, box, font_path, size):
    x1, y1, x2, y2 = box
    w, h = x2 - x1 - 2 * _PAD, y2 - y1 - 2 * _PAD
    f = _font(font_path, size)
    lines = _wrap(draw, text, f, w)
    lh = _fits(draw, lines, f, w, h)[1]
    total = lh * len(lines)
    y = y1 + (y2 - y1 - total) / 2
    for line in lines:
        lw = draw.textlength(line, font=f)
        draw.text((x1 + (x2 - x1 - lw) / 2, y), line, font=f, fill="black",
                  stroke_width=max(1, size // 14), stroke_fill="white")
        y += lh


def _render_box(gray, bbox):
    """Bubble fill area for a text box: the bright connected component under
    the bbox center on a 1.5x-expanded crop, shrunk 15%, unioned with bbox.
    Falls back to bbox when the center isn't bright or the bubble touches the
    crop edge (not closed)."""
    h, w = gray.shape
    x1, y1, x2, y2 = bbox
    bw, bh = x2 - x1, y2 - y1
    cx1 = max(0, int(x1 - 1.5 * bw))
    cy1 = max(0, int(y1 - 1.5 * bh))
    cx2 = min(w, int(x2 + 1.5 * bw))
    cy2 = min(h, int(y2 + 1.5 * bh))
    cx, cy = (x1 + x2) // 2 - cx1, (y1 + y2) // 2 - cy1
    crop = gray[cy1:cy2, cx1:cx2]
    if crop.size == 0 or crop[cy, cx] < 200:
        return bbox
    _, labels, stats, _ = cv2.connectedComponentsWithStats(
        (crop > 200).astype(np.uint8))
    lab = labels[cy, cx]
    if lab == 0:
        return bbox
    x, y, cw, ch, _ = stats[lab]
    if x <= 0 or y <= 0 or x + cw >= crop.shape[1] or y + ch >= crop.shape[0]:
        return bbox  # touches crop edge: not a closed bubble
    sx, sy = int(cw * 0.15), int(ch * 0.15)
    return (min(cx1 + x + sx, x1), min(cy1 + y + sy, y1),
            max(cx1 + x + cw - sx, x2), max(cy1 + y + ch - sy, y2))


def _widen(gray, box):
    """Grow tall-narrow boxes (vertical JP bubbles) sideways over clean bright
    regions so English can fit wider lines. Each side stops at the first
    column that isn't >=97% bright over [y1:y2], or the page edge."""
    x1, y1, x2, y2 = box
    h, w = gray.shape
    need = int(0.8 * (y2 - y1))
    if x2 - x1 >= need:
        return box
    def col_ok(x):
        return (gray[y1:y2, x] > 200).mean() >= 0.97
    l_done = r_done = False
    while x2 - x1 < need and not (l_done and r_done):
        if not l_done and x1 > 0 and col_ok(x1 - 1):
            x1 -= 1
        else:
            l_done = True
        if not r_done and x2 < w and col_ok(x2):
            x2 += 1
        else:
            r_done = True
    return (x1, y1, x2, y2)


def render(clean_img: Image.Image, blocks, settings) -> Image.Image:
    out = clean_img.copy().convert("RGB")
    draw = ImageDraw.Draw(out)
    gray = np.array(out.convert("L"))
    todo = []
    for b in blocks:
        if b.translation:
            box = _widen(gray, _render_box(gray, b.bbox))
            size, _, _ = _fit(draw, b.translation, box,
                              settings.resolved_font,
                              settings.font_min, settings.font_max)
            todo.append((box, size, b.translation))
    for (box, _, text), size in zip(todo, _sizes([t[1] for t in todo])):
        _draw_block(draw, text, box, settings.resolved_font, size)
    return out
