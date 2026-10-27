import logging
from pathlib import Path

from .models import TextBlock

log = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[2]
DETECTOR_PATH = REPO_ROOT / "models" / "manga_panel_detector_fp32.pt"

_model = None


def _detector():
    global _model
    if _model is None:
        from ultralytics import YOLO

        _model = YOLO(str(DETECTOR_PATH))
    return _model


def detect(img, conf=0.4, device=None):
    """Return (frames, texts) as lists of (x1,y1,x2,y2) int boxes."""
    kw = {"device": device} if device else {}
    res = _detector()(img, conf=conf, verbose=False, **kw)[0]
    frames, texts = [], []
    for b in res.boxes:
        box = tuple(int(v) for v in b.xyxy[0].tolist())
        (texts if int(b.cls) == 1 else frames).append(box)
    log.debug("detected %d frames %d texts", len(frames), len(texts))
    return frames, texts


def _inter(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    return max(0, x2 - x1) * max(0, y2 - y1)


def _vert_overlap(a, b):
    ov = min(a[3], b[3]) - max(a[1], b[1])
    return ov / max(1, min(a[3] - a[1], b[3] - b[1]))


def _order_row_wise(boxes, rtl):
    """Group boxes into rows (>50% vertical overlap of the shorter box),
    rows top->bottom, within a row right->left (rtl) or left->right."""
    n = len(boxes)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(n):
        for j in range(i + 1, n):
            if _vert_overlap(boxes[i], boxes[j]) > 0.5:
                pi, pj = find(i), find(j)
                if pi != pj:
                    parent[pi] = pj
    rows = {}
    for i in range(n):
        rows.setdefault(find(i), []).append(i)
    ordered = sorted(rows.values(), key=lambda g: min(boxes[i][1] for i in g))
    out = []
    for g in ordered:
        key = (lambda i: -boxes[i][2]) if rtl else (lambda i: boxes[i][0])
        out.extend(sorted(g, key=key))
    return out


def order_blocks(frames, texts, page, rtl=True):
    """Assign texts to frames, order everything into reading order.
    Returns (ordered_frames, ordered_blocks)."""
    assign = {}
    orphans = []
    for ti, t in enumerate(texts):
        best, best_area = -1, 0
        for fi, f in enumerate(frames):
            a = _inter(t, f)
            if a > best_area:
                best, best_area = fi, a
        if best_area > 0:
            assign.setdefault(best, []).append(ti)
        else:
            orphans.append(ti)

    # orphan texts sort among frames as pseudo-frames
    items = [("f", fi, f) for fi, f in enumerate(frames)]
    items += [("t", ti, texts[ti]) for ti in orphans]
    seq = _order_row_wise([b for _, _, b in items], rtl)

    ordered_frames = []
    blocks = []
    n = 0
    for i in seq:
        kind, idx, box = items[i]
        if kind == "t":
            blocks.append(TextBlock(f"p{page}_b{n}", texts[idx], -1))
            n += 1
            continue
        frame_idx = len(ordered_frames)
        ordered_frames.append(box)
        inner = [texts[ti] for ti in assign.get(idx, [])]
        for ti in _order_row_wise(inner, rtl):
            blocks.append(TextBlock(f"p{page}_b{n}", inner[ti], frame_idx))
            n += 1
    return ordered_frames, blocks
