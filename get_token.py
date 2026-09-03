"""
Одноразовый скрипт: получает токен Яндекс.Музыки через официальный device-auth
flow библиотеки yandex-music и сохраняет его в token.txt (в .gitignore).

Запуск:
    python get_token.py
"""
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from yandex_music import Client

TOKEN_FILE = Path(__file__).parent / "token.txt"


def on_code(code):
    print(f"Открой ссылку:\n  {code.verification_url}")
    print(f"И введи код: {code.user_code}\n")
    print("Жду подтверждения входа...")


def main():
    client = Client()
    token = client.device_auth(on_code=on_code)
    TOKEN_FILE.write_text(token.access_token, encoding="utf-8")
    print(f"\nГотово. Токен сохранён в {TOKEN_FILE}")


if __name__ == "__main__":
    main()
