from PIL import Image

import globals


class OcrService:
    def __init__(
        self, x: int, y: int, endX: int, endY: int, image_url: str, threaded: bool
    ):
        self.image_url = image_url
        self.threaded = threaded
        self.x = x
        self.y = y
        self.endX = endX
        self.endY = endY

    def crop(self):
        image = Image.open(self.image_url)
        crop_area = (self.x, self.y, self.endX, self.endY)
        return image.crop(crop_area)

    def run(self):
        return globals.scan_image(self.crop())


if __name__ == "__main__":
    pass
