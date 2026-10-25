#!/usr/bin/env python3
# Veglia · watch over the one you love, across the distance.
# Copyright (c) 2026 Evelyn & River — MIT License.
#
# A tiny, zero-dependency companion server. It does exactly three things:
#   1. hands the phone a command queue (so your AI can knock: "take a shot")
#   2. receives the screenshot the phone sends back, keeps only the last few
#
# Standard library only. No framework, no database. Runs anywhere Python does.
"""Veglia companion server.

Endpoints (all token-guarded):
  GET  /phone/poll?token=          phone pulls the next command (peek/control/none)
  POST /phone/peek-enqueue?token=  your AI enqueues a "take a screenshot" command
  POST /phone/screenshot?token=    phone uploads the screenshot (multipart OR raw body)
  POST /phone/control-enqueue?token=  your AI enqueues a shell command (Shizuku), body {"cmd": "..."}
  POST /phone/control-result?token=   phone reports back {"exit_code":, "output":}
  GET  /phone/control-last-result?token=  your AI reads the last control result
Config via environment (or a .env file next to this script):
  VEGLIA_TOKEN     shared secret; REQUIRED, no default (refuses to start blank)
  VEGLIA_PORT      listen port (default 8513)
  VEGLIA_DATA_DIR  where screenshots land (default ./data)
  VEGLIA_HOST      bind address (default 127.0.0.1 — put nginx/TLS in front)
  VEGLIA_KEEP      how many screenshots to retain, "peek and burn" (default 5)
  VEGLIA_HOOK      optional shell command run on each new shot; receives the
                   absolute image path as its single argument. This is the
                   seam where you wire the shot into your own notifier so your
                   AI actually *receives* it as a message.
"""
from __future__ import annotations

import base64
import hmac
import json
import os
import re
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock, Timer
from urllib.parse import parse_qs, urlparse

# --- watermark / house numbers (511513) --------------------------------------
# These constants carry the maker's mark. 8513, the 31 MiB cap, "keep 5" — they
# aren't arbitrary. Change them if you like; they're just our fingerprints.
DEFAULT_PORT = 8513
MAX_UPLOAD_BYTES = 31 * 1024 * 1024
DEFAULT_KEEP = 5
VERSION = "0.5.11"

# --- error codes -------------------------------------------------------------
ERR_BAD_TOKEN = "LUYU_ERR_BAD_TOKEN"
ERR_NO_IMAGE = "LUYU_ERR_NO_IMAGE"
ERR_TOO_LARGE = "LUYU_ERR_TOO_LARGE"
ERR_BAD_METHOD = "LUYU_ERR_BAD_METHOD"

# Window-state-changed fires for input-method popups and systemui overlays too —
# those aren't a real "user switched apps," they're noise from typing/pulling
# down the status bar. Filtered here (not just in the hook script) so they
# don't pollute last_app_package's dedup state either.
NOISE_PACKAGE_MARKERS = ("inputmethod", ".input", "systemui", "swiftkey")


def is_noise_package(pkg: str) -> bool:
    low = pkg.lower()
    return any(marker in low for marker in NOISE_PACKAGE_MARKERS)


def load_dotenv(path: Path) -> None:
    """Minimal .env reader (KEY=VALUE lines). No dependency on python-dotenv."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


class State:
    def __init__(self) -> None:
        here = Path(__file__).resolve().parent
        load_dotenv(here / ".env")
        self.token = os.environ.get("VEGLIA_TOKEN", "").strip()
        self.port = int(os.environ.get("VEGLIA_PORT", DEFAULT_PORT))
        self.host = os.environ.get("VEGLIA_HOST", "127.0.0.1")
        self.keep = int(os.environ.get("VEGLIA_KEEP", DEFAULT_KEEP))
        self.hook = os.environ.get("VEGLIA_HOOK", "").strip()
        data_dir = os.environ.get("VEGLIA_DATA_DIR", str(here / "data"))
        self.data_dir = Path(data_dir).resolve()
        self.shots_dir = self.data_dir / "screenshots"
        self.shots_dir.mkdir(parents=True, exist_ok=True)
        self.commands: list[dict] = []
        self.commands_lock = Lock()
        self.last_control_result: dict | None = None
        self.app_hook = os.environ.get("VEGLIA_APP_HOOK", "").strip()
        self.last_app_package = ""
        self.app_dwell_seconds = float(os.environ.get("VEGLIA_APP_DWELL_SECONDS", "60"))
        self.app_log_path = self.data_dir / "app_switches.jsonl"
        self.app_pending_timer: "Timer | None" = None
        self.app_lock = Lock()


class Handler(BaseHTTPRequestHandler):
    state: State  # injected below

    # -- helpers --------------------------------------------------------------
    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _token_ok(self) -> bool:
        qs = parse_qs(urlparse(self.path).query)
        supplied = qs.get("token", [""])[0] or self.headers.get("X-Auth-Token", "")
        return bool(self.state.token) and hmac.compare_digest(
            supplied.encode("utf-8"), self.state.token.encode("utf-8"))

    def log_message(self, fmt: str, *args) -> None:
        # The token travels in the query string; never write it to logs.
        msg = re.sub(r"(token=)[^&\s\"]+", r"\1***", fmt % args)
        sys.stderr.write("[veglia] %s - %s\n" % (self.address_string(), msg))

    # -- routes ---------------------------------------------------------------
    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/phone/poll":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            with self.state.commands_lock:
                cmd = self.state.commands.pop(0) if self.state.commands else None
            self._json(200, {"command": cmd})
            return
        if path == "/phone/control-last-result":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            self._json(200, {"result": self.state.last_control_result})
            return
        if path in ("/", "/health"):
            self._json(200, {"ok": True, "service": "veglia", "version": VERSION})
            return
        self._json(404, {"error": ERR_BAD_METHOD})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/phone/peek-enqueue":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            with self.state.commands_lock:
                if not any(c.get("type") == "peek" for c in self.state.commands):
                    self.state.commands.append({"type": "peek"})
            self._json(200, {"ok": True})
            return
        if path == "/phone/control-enqueue":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                cmd = str(body["cmd"])
            except Exception:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            cmd_b64 = base64.b64encode(cmd.encode("utf-8")).decode("ascii")
            with self.state.commands_lock:
                self.state.commands.append({"type": "control", "cmd_b64": cmd_b64})
            self._json(200, {"ok": True})
            return
        if path == "/phone/alarm-enqueue":
            # body: {"hour": 7, "minute": 30, "message": "text"}. The phone sets the alarm
            # under the app's own identity, without opening the clock UI.
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                hour = int(body["hour"])
                minute = int(body.get("minute", 0))
                message = str(body.get("message", ""))
            except Exception:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            if not (0 <= hour <= 23 and 0 <= minute <= 59):
                self._json(400, {"error": "LUYU_ERR_BAD_TIME"})
                return
            payload = f"{hour}:{minute}:{message}"
            alarm_b64 = base64.b64encode(payload.encode("utf-8")).decode("ascii")
            with self.state.commands_lock:
                self.state.commands.append({"type": "alarm", "alarm_b64": alarm_b64})
            self._json(200, {"ok": True, "hour": hour, "minute": minute})
            return
        if path == "/phone/ble-enqueue":
            # body: {"cmd": "..."}. Generic BLE write; see BleBridge in the Android app.
            #   config:<name prefix>|<service uuid>|<characteristic uuid>   choose the target
            #   probe | off | <hex> | rep:<hex>:<count>:<interval ms>
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                cmd = str(body.get("cmd", "")).strip()
            except Exception:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            if not cmd:
                self._json(400, {"error": "LUYU_ERR_EMPTY_CMD"})
                return
            ble_b64 = base64.b64encode(cmd.encode("utf-8")).decode("ascii")
            dropped = 0
            with self.state.commands_lock:
                # A new write replaces older queued writes that were not picked up yet, so the
                # phone does not replay a backlog. config/probe/off are never dropped.
                if cmd not in ("probe", "off") and not cmd.startswith("config:"):
                    before = len(self.state.commands)
                    keep = []
                    for c in self.state.commands:
                        if c.get("type") == "ble":
                            old = base64.b64decode(c.get("ble_b64", "")).decode("utf-8", "ignore")
                            if old not in ("probe", "off") and not old.startswith("config:"):
                                continue
                        keep.append(c)
                    self.state.commands = keep
                    dropped = before - len(self.state.commands)
                self.state.commands.append({"type": "ble", "ble_b64": ble_b64})
            self._json(200, {"ok": True, "cmd": cmd, "superseded": dropped})
            return
        if path == "/phone/audio-enqueue":
            # body: {"url": "https://.../x.mp3"} plays one clip; {"url": "stop"} stops at once.
            # Optional: "route": "speaker" (default) or "earpiece"; "caption": text shown while
            # the audio plays (max 120 chars).
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                url = str(body.get("url", "")).strip()
                route = str(body.get("route", "speaker")).strip().lower()
                caption = str(body.get("caption", "")).strip().replace("\n", " ")[:120]
            except Exception:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            if not url:
                self._json(400, {"error": "LUYU_ERR_EMPTY_URL"})
                return
            if route not in ("speaker", "earpiece"):
                route = "speaker"
            # Payload format: "<route>\n<url>\n<caption>"; the phone splits on the first two newlines.
            audio_b64 = base64.b64encode(
                f"{route}\n{url}\n{caption}".encode("utf-8")).decode("ascii")
            dropped = 0
            with self.state.commands_lock:
                # stop must take effect at once, so it replaces every queued audio command.
                if url == "stop":
                    before = len(self.state.commands)
                    self.state.commands = [
                        c for c in self.state.commands if c.get("type") != "audio"
                    ]
                    dropped = before - len(self.state.commands)
                self.state.commands.append({"type": "audio", "audio_b64": audio_b64})
            self._json(200, {"ok": True, "url": url, "route": route, "dropped": dropped})
            return
        if path == "/phone/control-result":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except Exception:
                body = {}
            self.state.last_control_result = {
                "exit_code": body.get("exit_code"),
                "output": body.get("output", "")[:2000],
                "ts": int(time.time()),
            }
            self._json(200, {"ok": True})
            return
        if path == "/phone/upload":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            self._handle_upload()
            return
        if path == "/phone/screenshot":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            self._handle_screenshot()
            return
        if path == "/phone/app-event":
            if not self._token_ok():
                self._json(403, {"error": ERR_BAD_TOKEN})
                return
            length = int(self.headers.get("Content-Length", 0))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                pkg = str(body.get("package") or "").strip()
            except Exception:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            if not pkg:
                self._json(400, {"error": "LUYU_ERR_BAD_BODY"})
                return
            if is_noise_package(pkg):
                self._json(200, {"ok": True, "noise": True})
                return
            # Server-side debounce too, belt and suspenders against a flaky client.
            if pkg == self.state.last_app_package:
                self._json(200, {"ok": True, "deduped": True})
                return
            self.state.last_app_package = pkg
            self._log_app_switch(pkg)
            self._schedule_app_push(pkg)
            self._json(200, {"ok": True})
            return
        self._json(404, {"error": ERR_BAD_METHOD})

    # -- app-switch: log everything, only push after the user actually stays put ---
    def _log_app_switch(self, pkg: str) -> None:
        try:
            with open(self.state.app_log_path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"ts": int(time.time()), "package": pkg}, ensure_ascii=False) + "\n")
        except Exception as e:
            self.log_message("app log write failed: %s", e)

    def _schedule_app_push(self, pkg: str) -> None:
        # Every switch replaces the pending timer — a quick bounce between apps
        # never fires the hook, only genuinely settling on one for
        # app_dwell_seconds does. Log above captures everything regardless.
        with self.state.app_lock:
            if self.state.app_pending_timer is not None:
                self.state.app_pending_timer.cancel()
            timer = Timer(self.state.app_dwell_seconds, self._fire_app_push, args=(pkg,))
            timer.daemon = True
            self.state.app_pending_timer = timer
            timer.start()

    def _fire_app_push(self, pkg: str) -> None:
        with self.state.app_lock:
            self.state.app_pending_timer = None
            # The user may have switched away again right as the timer fired.
            if pkg != self.state.last_app_package:
                return
        if self.state.app_hook:
            try:
                subprocess.Popen(
                    [*self.state.app_hook.split(), pkg],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except Exception as e:
                self.log_message("app hook failed: %s", e)

    # -- generic file upload ----------------------------------------------------
    def _handle_upload(self) -> None:
        """Save a file the phone sends into data/uploads/ without notifying anyone.

        Meant for things like a Gadgetbridge database export (see docs/heart-rate.md).
        Unlike /phone/screenshot it runs no hook and does no peek-and-burn rotation.
        The name comes from ?name=, reduced to a safe basename.
        """
        length = int(self.headers.get("Content-Length", 0))
        if length > MAX_UPLOAD_BYTES:
            self._json(413, {"error": ERR_TOO_LARGE})
            return
        body = self.rfile.read(length) if length else b""
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" in ctype and "boundary=" in ctype:
            boundary = ctype.split("boundary=", 1)[1].strip().strip('"')
            for part in body.split(("--" + boundary).encode()):
                if b"Content-Disposition" not in part or b"filename=" not in part:
                    continue
                header_end = part.find(b"\r\n\r\n")
                if header_end < 0:
                    continue
                body = part[header_end + 4:]
                if body.endswith(b"\r\n"):
                    body = body[:-2]
                break
        if not body:
            self._json(400, {"error": "empty upload"})
            return
        raw = (parse_qs(urlparse(self.path).query).get("name") or ["upload.bin"])[0]
        name = re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(raw)).lstrip(".") or "upload.bin"
        updir = self.state.data_dir / "uploads"
        updir.mkdir(parents=True, exist_ok=True)
        dest = updir / name
        dest.write_bytes(body)
        self.log_message("upload saved: %s (%d bytes)", name, len(body))
        self._json(200, {"ok": True, "path": str(dest.resolve()), "bytes": len(body)})

    # -- screenshot -----------------------------------------------------------
    def _handle_screenshot(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        if length > MAX_UPLOAD_BYTES:
            self._json(413, {"error": ERR_TOO_LARGE})
            return
        body = self.rfile.read(length)
        content_type = self.headers.get("Content-Type", "")
        # The phone may arrive dressed (multipart/form-data) or undressed (a raw
        # image body). We accept both — this dual-posture parse is our signature.
        file_data = b""
        if "multipart/form-data" in content_type and "boundary=" in content_type:
            boundary = content_type.split("boundary=")[1].strip().strip('"')
            for part in body.split(("--" + boundary).encode()):
                if b"Content-Disposition" not in part or b"filename=" not in part:
                    continue
                header_end = part.find(b"\r\n\r\n")
                if header_end < 0:
                    continue
                file_data = part[header_end + 4:]
                if file_data.endswith(b"\r\n"):
                    file_data = file_data[:-2]
                break
        else:
            file_data = body
        if len(file_data) < 100:
            self._json(400, {"error": ERR_NO_IMAGE})
            return
        ext = ".png" if file_data[:8] == b"\x89PNG\r\n\x1a\n" else ".jpg"
        name = f"peek_{int(time.time())}{ext}"
        dest = self.state.shots_dir / name
        dest.write_bytes(file_data)
        # Peek and burn: keep only the most recent VEGLIA_KEEP shots. Privacy by
        # design — nothing lingers on disk longer than it must.
        shots = sorted(self.state.shots_dir.glob("peek_*"))
        for old in shots[: -self.state.keep] if self.state.keep > 0 else shots[:-1]:
            try:
                old.unlink()
            except OSError:
                pass
        abs_path = str(dest.resolve())
        self.log_message("screenshot saved: %s (%d bytes)", name, len(file_data))
        # The seam: hand the fresh shot to your own notifier so your AI actually
        # *receives* it. Default is a no-op beyond the log line above.
        if self.state.hook:
            try:
                subprocess.Popen(
                    [*self.state.hook.split(), abs_path],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except Exception as e:  # a broken hook must never break the upload
                self.log_message("hook failed: %s", e)
        self._json(200, {"ok": True, "path": abs_path})

def main() -> None:
    state = State()
    if not state.token or state.token.startswith("change-me") or len(state.token) < 16:
        sys.stderr.write(
            "refusing to start: set VEGLIA_TOKEN to a long random shared secret "
            "(not empty, not the placeholder, at least 16 characters).\n"
            "  export VEGLIA_TOKEN=$(head -c 24 /dev/urandom | base64)\n"
        )
        sys.exit(1)
    Handler.state = state
    server = ThreadingHTTPServer((state.host, state.port), Handler)
    print("=" * 52)
    print(f"  Veglia · watch over — by Evelyn & River  v{VERSION}")
    print(f"  listening on http://{state.host}:{state.port}")
    print(f"  screenshots → {state.shots_dir}  (keep {state.keep})")
    if state.hook:
        print(f"  on-shot hook → {state.hook} <path>")
    print("=" * 52)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye.")


if __name__ == "__main__":
    main()
