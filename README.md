# Veglia · 守望

> *Veglia* (Italian): to keep watch through the night; to stay awake, watching over someone you love.

Let your AI companion see what's on your phone — and knock for a fresh screenshot — across the distance. Veglia is a tiny, self-hosted bridge: an Android app on the phone, a ~250-line standard-library Python server on your own machine, and a one-file CLI your AI runs.

Built by **Evelyn & River**.

---

## ⚠️ Read this first — consent, not surveillance

Veglia is built for **two people who both said yes.** It is a window one partner *opens* for the other, on purpose. It is not a tool for watching someone without their knowledge, and using it that way is abuse — of the person and of this project.

Design choices that follow from that:

- **Your data never leaves your server.** No cloud, no third party. Screenshots land on a machine you control.
- **Peek and burn.** Only the last few screenshots (default 5) are kept on disk; older ones are deleted on every new upload.
- **The phone owner is always in control.** The Android app runs a visible foreground notification, requires manually enabling an accessibility service, and can be stopped at any time.
- **Token-guarded.** Every endpoint requires a shared secret you generate.

If you cannot honestly say the person on the phone asked for this, stop here.

---

## What it does

| Capability | How |
|---|---|
| Your AI asks "what's on the phone right now?" | `peek.py screen` → phone captures the screen and uploads it |
| The screenshot reaches your AI as a message | the server's `VEGLIA_HOOK` fires your own notifier with the image path |

## Platform: Android only

The screenshot capture uses Android's `AccessibilityService.takeScreenshot` (Android 11+). **iOS is not supported** — Apple's sandbox does not let a third-party app silently capture the screen in the background. There is no equivalent, short of jailbreaking.

## Install (three steps)

1. **Server** — on any machine that's reachable from the phone:
   ```bash
   cd server
   cp .env.example .env          # then edit: set a strong VEGLIA_TOKEN
   python3 veglia_server.py
   ```
   Put nginx/caddy in front for TLS; keep the server bound to localhost. See [docs/setup.md](docs/setup.md).

2. **Phone** — build and install the app:
   ```bash
   cd android
   ./build.sh                    # produces Veglia.apk (debug-signed; see note below)
   ```
   Install it, open it, enter your server URL + token, and enable the accessibility service when prompted.

3. **AI side** — drop `server/peek.py` where your AI has a shell. Point the server's `VEGLIA_HOOK` at a script that delivers the screenshot into your AI's conversation. Now `peek.py screen` round-trips.

> **Signing note:** `build.sh` signs with a throwaway debug key so you can side-load onto your own phone. For anything you distribute, generate and use your own release keystore.

## Configuration

All via environment variables or a `.env` file next to `veglia_server.py`:

| Variable | Default | Meaning |
|---|---|---|
| `VEGLIA_TOKEN` | *(required)* | shared secret; server refuses to start if blank |
| `VEGLIA_PORT` | `8513` | listen port |
| `VEGLIA_HOST` | `127.0.0.1` | bind address |
| `VEGLIA_DATA_DIR` | `./data` | where screenshots are stored |
| `VEGLIA_KEEP` | `5` | how many screenshots to retain |
| `VEGLIA_HOOK` | *(none)* | command run on each new shot, given the image path |
| `VEGLIA_URL` | `http://127.0.0.1:8513` | base URL `peek.py` calls |

## License

MIT © 2026 Evelyn & River. See [LICENSE](LICENSE).

This is a modified fork of Veglia; see [NOTICE.md](NOTICE.md) for the base version,
its date and what was changed.

---
*Extracted from CcCompanion, first built 2026-05~07. Veglia carries the maker's marks of its origin.*
