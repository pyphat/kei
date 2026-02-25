class YoloService:
    """
    @
    """

    def __init__(self, model, image_url: str, threaded: bool):
        self.image_url = image_url
        self.threaded = threaded
        self.model = model
