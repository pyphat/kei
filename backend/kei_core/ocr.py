_ocr = None


def get_ocr(force_cpu=False):
    """Lazy manga-ocr engine; instance is callable on a PIL crop."""
    global _ocr
    if _ocr is None:
        from manga_ocr import MangaOcr

        _ocr = MangaOcr(force_cpu=force_cpu)
    return _ocr


class PassthroughOCR:
    def __init__(self, text=""):
        self.text = text

    def __call__(self, crop):
        return self.text
