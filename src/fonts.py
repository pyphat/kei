from tkextrafont import Font

SATOSHI = None
SHIPPORI_ANTIQUE = None


def load_font():
    global SATOSHI, SHIPPORI_ANTIQUE
    SATOSHI = Font(file="C:/Coding/Python/kei/fonts/Satoshi.ttf", family="Satoshi")
    SHIPPORI_ANTIQUE = Font(
        file="C:/Coding/Python/kei/fonts/ShipporiAntique.ttf", family="Shippori Antique"
    )
