from google import genai
from manga_ocr import MangaOcr

print("initializing manga-ocr")
mangaOcr = MangaOcr()
print("initializing gemini client")
client = genai.Client(api_key="AIzaSyAzZ91REFKB1nLrDm0MMa8UFT9hy1IHe8I")


# Manga OCR Scan
def scan(cropped):
    return mangaOcr(cropped)


# Translate with ChatGPT
def translate(text):
    # chatgpt
    try:
        response = client.models.generate_content(
            model="gemini-3-flash-preview",
            contents=f"Translate the following from Japanese to English, no need for other answers. Just the translation {text}",
        )
        return response.text
    except:
        return f"Translation failed. 🎉🎉🎉"
