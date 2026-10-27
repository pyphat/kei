from kei_core.detect import order_blocks


def test_reading_order_rtl():
    # two frames on the top row side by side, one wide frame below
    f_left = (0, 0, 100, 100)
    f_right = (200, 0, 300, 100)
    f_bottom = (0, 200, 300, 300)
    t_right = (230, 10, 280, 40)   # inside f_right
    t_left = (20, 10, 70, 40)      # inside f_left
    t_bottom = (120, 220, 180, 260)  # inside f_bottom
    t_orphan = (320, 210, 380, 250)  # no frame overlap -> pseudo-frame
    frames, blocks = order_blocks(
        [f_left, f_right, f_bottom],
        [t_right, t_left, t_bottom, t_orphan], page=0, rtl=True)
    # rtl: right frame's text, then left frame's; bottom row: orphan is
    # rightmost so it precedes the bottom frame's text
    assert [b.id for b in blocks] == ["p0_b0", "p0_b1", "p0_b2", "p0_b3"]
    assert [b.bbox for b in blocks] == [t_right, t_left, t_orphan, t_bottom]
    assert [b.frame for b in blocks] == [0, 1, -1, 2]


def test_reading_order_ltr():
    f_left = (0, 0, 100, 100)
    f_right = (200, 0, 300, 100)
    t_right = (230, 10, 280, 40)
    t_left = (20, 10, 70, 40)
    frames, blocks = order_blocks([f_left, f_right], [t_right, t_left],
                                  page=2, rtl=False)
    assert [b.bbox for b in blocks] == [t_left, t_right]
    assert [b.id for b in blocks] == ["p2_b0", "p2_b1"]


def test_texts_within_frame_order():
    f = (0, 0, 300, 100)
    t1 = (10, 10, 60, 40)    # left
    t2 = (200, 10, 260, 40)  # right
    _, blocks = order_blocks([f], [t1, t2], page=0, rtl=True)
    assert [b.bbox for b in blocks] == [t2, t1]
