from googletrans import Translator
from manga_ocr import MangaOcr

mangaOcr = MangaOcr()
translator = Translator()


def scan_image(cropped):
    return mangaOcr(cropped)
