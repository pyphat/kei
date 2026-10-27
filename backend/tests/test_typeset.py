import cv2
import numpy as np

from kei_core.typeset import _render_box


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
