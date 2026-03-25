from concurrent.futures import ThreadPoolExecutor, as_completed

import globals
from service.ocrService import OcrService
from service.yoloService import YoloService


class MangaPage:
    def __init__(self, image_path: str, target_class="text", workers=6):
        self.image_path = image_path
        self.target_class = target_class
        self.workers = workers

        self.bubbles = []

        self._process_page()

    def _ocr_single(self, det):

        x1 = det["x1"]
        y1 = det["y1"]
        x2 = det["x2"]
        y2 = det["y2"]

        ocr_service = OcrService(x1, y1, x2, y2, self.image_path)

        text = ocr_service.run()

        return {"bbox": det, "text": text}

    def _process_page(self):

        yolo_service = YoloService(self.image_path)
        detections = yolo_service.run()

        filtered = [d for d in detections if d["class"] == self.target_class]

        with ThreadPoolExecutor(max_workers=self.workers) as executor:
            futures = [executor.submit(self._ocr_single, det) for det in filtered]

            for future in as_completed(futures):
                self.bubbles.append(future.result())

    def get_bubbles(self):
        return self.bubbles

    def find_bubble(self, x, y):

        for bubble in self.bubbles:
            det = bubble["bbox"]

            if det["x1"] <= x <= det["x2"] and det["y1"] <= y <= det["y2"]:
                return bubble

        return None
