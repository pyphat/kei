from PIL import Image

import globals


class OcrService:
    def __init__(self, x: int, y: int, endX: int, endY: int, image_url: str):
        self.image_url = image_url
        self.x = x
        self.y = y
        self.endX = endX
        self.endY = endY

    def crop(self):
        image = Image.open(self.image_url)
        crop_area = (
            min(self.x, self.endX),
            min(self.y, self.endY),
            max(self.endX, self.x),
            max(self.y, self.endY),
        )
        return image.crop(crop_area)

    def run(self):
        return globals.scan(self.crop())


if __name__ == "__main__":
    pass
