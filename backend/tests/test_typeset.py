import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from kei_core.typeset import _render_box, _sizes, _widen, _wrap


def test_wrap_breaks_cjk():
    draw = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    f = ImageFont.load_default(16)
    text = "これは長い日本語の文章です" * 2
    lines = _wrap(draw, text, f, 60)
    assert len(lines) > 1
    assert "".join(lines) == text


def test_wrap_keeps_latin_words():
    draw = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    f = ImageFont.load_default(16)
    lines = _wrap(draw, "hello wonderful world", f, 60)
    assert " ".join(lines) == "hello wonderful world"
    assert set(" ".join(lines).split()) == {"hello", "wonderful", "world"}
    assert all(not line.startswith(" ") and not line.endswith(" ") for line in lines)


def test_wrap_thai_breaks_at_words():
    draw = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    f = ImageFont.load_default(16)
    text = "ภาษาไทยเป็นภาษาที่ไม่มีการเว้นวรรคระหว่างคำ"
    from pythainlp.tokenize import word_tokenize
    lines = _wrap(draw, text, f, 60)
    assert len(lines) > 1
    assert "".join(lines) == text
    cuts, pos = set(), 0
    for w in word_tokenize(text, engine="newmm"):
        pos += len(w); cuts.add(pos)
    pos = 0
    for line in lines[:-1]:
        pos += len(line); assert pos in cuts


def test_render_box_uses_bubble():
    img = np.full((400, 400), 128, np.uint8)  # gray page
    cv2.ellipse(img, (200, 200), (55, 100), 0, 0, 360, 255, -1)
    cv2.ellipse(img, (200, 200), (55, 100), 0, 0, 360, 0, 3)
    narrow = (185, 140, 215, 260)  # tall thin text box inside the bubble
    rb = _render_box(img, narrow)
    assert rb[2] - rb[0] > (narrow[2] - narrow[0]) * 2
    assert rb[0] <= narrow[0] and rb[2] >= narrow[2]  # contains the text box


def test_render_box_dark_background():
    img = np.full((400, 400), 40, np.uint8)
    bbox = (190, 140, 210, 260)
    assert _render_box(img, bbox) == bbox


def test_widen_grows_narrow_box_on_white():
    img = np.full((400, 400), 255, np.uint8)
    img[:, 150] = 0  # black vertical line left of the box
    box = (200, 100, 230, 220)  # w=30, h=120 -> needs w>=96
    x1, y1, x2, y2 = _widen(img, box)
    assert y1 == 100 and y2 == 220
    assert x1 >= 151  # stopped at the black line, didn't cross it
    assert x2 - x1 >= int(0.8 * 120)  # reached target width via the right side


def test_sizes_cap_at_median():
    assert _sizes([28, 10, 10]) == [10, 10, 10]
    assert _sizes([28]) == [28]  # single block unchanged
    assert _sizes([]) == []
