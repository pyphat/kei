import globals

"""
unused, might update soon.
"""


class TranslateService:
    def __init__(self, japanese_text, threaded):
        self.japanese_text = japanese_text
        self.threaded = threaded

    def run(self) -> str:
        return globals.translate(self.japanese_text)
