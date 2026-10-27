from dataclasses import dataclass, field, asdict


@dataclass
class TextBlock:
    id: str
    bbox: tuple[int, int, int, int]
    frame: int = -1  # index into the page's ordered frame list, -1 if none
    source: str = ""
    translation: str = ""

    def to_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, d):
        return cls(d["id"], tuple(d["bbox"]), d.get("frame", -1),
                   d.get("source", ""), d.get("translation", ""))
