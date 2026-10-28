import cv2
import numpy as np

from kei_core.typeset import _render_box, _sizes, _widen


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
