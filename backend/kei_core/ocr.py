_ocr = None


def get_ocr():
    """Lazy manga-ocr engine; instance is callable on a PIL crop."""
    global _ocr
    if _ocr is None:
        from manga_ocr import MangaOcr

        _ocr = MangaOcr()
    return _ocr


class PassthroughOCR:
    def __init__(self, text=""):
        self.text = text

    def __call__(self, crop):
        return self.text
