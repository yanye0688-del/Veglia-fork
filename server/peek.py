#!/usr/bin/env python3
# Veglia · the AI-side command. Copyright (c) 2026 Evelyn & River — MIT License.
"""Peek: your AI's little window onto the phone.

  python3 peek.py              # knock: ask the phone to take & upload a screenshot

Config is read from the environment or a .env file next to this script:
  VEGLIA_TOKEN   shared secret (must match the server)
  VEGLIA_URL     server base URL (default http://127.0.0.1:8513)

After running, the phone uploads the shot within a few seconds. Wire the
server's VEGLIA_HOOK to your own notifier so the image reaches your AI as a
message it can actually open.
"""
from __future__ import annotations

import os
import sys
import urllib.request
from pathlib import Path


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


load_dotenv(Path(__file__).resolve().parent / ".env")
TOKEN = os.environ.get("VEGLIA_TOKEN", "").strip()
BASE = os.environ.get("VEGLIA_URL", "http://127.0.0.1:8513").rstrip("/")


def peek_screen() -> None:
    req = urllib.request.Request(
        f"{BASE}/phone/peek-enqueue?token={TOKEN}", method="POST", data=b"",
    )
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception as e:
        print(f"could not reach server: {e}")
        return
    print("knock sent — the phone will grab a screenshot and upload it shortly.")


def main() -> None:
    if not TOKEN:
        print("set VEGLIA_TOKEN (see .env.example)")
        sys.exit(1)
    peek_screen()


if __name__ == "__main__":
    main()
