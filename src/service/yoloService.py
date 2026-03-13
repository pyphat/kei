from ultralytics import YOLO  # pyright: ignore[reportPrivateImportUsage]

import globals


class YoloService:
    def __init__(self, image_url: str, threaded: bool):
        self.image_url = image_url
        self.threaded = threaded
        self.model = globals.get_model()

    def run(self):
        with globals._yolo_lock:
            results = self.model(self.image_url)

            detections = []

            for result in results:
                for box in result.boxes:
                    x1, y1, x2, y2 = box.xyxy[0].tolist()

                    confidence = float(box.conf[0])
                    class_id = int(box.cls[0])
                    class_name = self.model.names[class_id]

                    detections.append(
                        {
                            "x1": x1,
                            "y1": y1,
                            "x2": x2,
                            "y2": y2,
                            "class": class_name,
                            "confidence": confidence,
                        }
                    )

        return detections
