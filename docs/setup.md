# Server setup

Veglia's server is a single standard-library Python file. No pip install, no database.

## 1. Generate a token

```bash
head -c 24 /dev/urandom | base64
```

Put it in `server/.env` as `VEGLIA_TOKEN`. The same value goes into the phone app.

## 2. Run it

```bash
cd server
cp .env.example .env      # edit VEGLIA_TOKEN
python3 veglia_server.py
```

The server binds to `127.0.0.1:8513` by default and refuses to start with a blank token.

Run it under a process manager so it survives reboots — e.g. systemd or pm2:

```ini
# /etc/systemd/system/veglia.service
[Unit]
Description=Veglia companion server
After=network.target

[Service]
WorkingDirectory=/path/to/veglia/server
ExecStart=/usr/bin/python3 /path/to/veglia/server/veglia_server.py
Restart=always

[Install]
WantedBy=multi-user.target
```

## 3. Put TLS in front

The phone talks to the server over the internet, so terminate HTTPS with nginx or caddy and keep Veglia bound to localhost. Minimal nginx:

```nginx
location /phone/ {
    proxy_pass http://127.0.0.1:8513;
    client_max_body_size 32m;   # screenshots can be a few MB
}
```

Point the phone app's "Server address" at `https://your-domain` (no trailing slash).

## 4. Wire the screenshot to your AI (`VEGLIA_HOOK`)

When a screenshot arrives, the server can run a command of your choosing with the
image's absolute path as its single argument. This is how the shot reaches your AI
as a message it can actually open.

```bash
# .env
VEGLIA_HOOK=/home/me/notify-my-ai.sh
```

```bash
#!/bin/bash
# notify-my-ai.sh — $1 is the absolute path to the new screenshot.
# Deliver it however your AI receives messages. For example, if your AI runs
# in a tmux session, inject a line telling it to open the image:
tmux load-buffer - <<< "[peek] new screenshot: $1 — open it with your file tool and tell me what you see."
tmux paste-buffer -t my-ai-session -p
tmux send-keys -t my-ai-session Enter
```

Without a hook, the server just saves the file and logs the path.

## Endpoints (reference)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET  | `/phone/poll?token=` | token | phone pulls next command |
| POST | `/phone/peek-enqueue?token=` | token | AI enqueues a "peek" |
| POST | `/phone/screenshot?token=` | token | phone uploads a screenshot |

## Extra endpoints (added in this fork)

All of them are `POST`, token-guarded (`?token=<VEGLIA_TOKEN>`), with a JSON body. The phone picks
the queued command up on its next poll (every few seconds).

```bash
BASE=http://127.0.0.1:8513; T=<VEGLIA_TOKEN>

# Run a shell command through Shizuku (Shizuku must be running and permission granted)
curl -s -X POST "$BASE/phone/control-enqueue?token=$T" -d '{"cmd":"input keyevent 26"}'
curl -s "$BASE/phone/control-last-result?token=$T"          # read the result

# Set an alarm (app's own identity, no clock UI shown)
curl -s -X POST "$BASE/phone/alarm-enqueue?token=$T" -d '{"hour":7,"minute":30,"message":"wake up"}'

# Play audio on the phone; route "earpiece" is optional, caption is shown while it plays
curl -s -X POST "$BASE/phone/audio-enqueue?token=$T" -d '{"url":"https://example.com/a.mp3","caption":"hello"}'
curl -s -X POST "$BASE/phone/audio-enqueue?token=$T" -d '{"url":"stop"}'
```

### Generic BLE write

The bridge connects to BLE peripherals by advertised-name prefix and writes raw bytes to one
characteristic. It ships with no device knowledge: tell it what to look for first.

```bash
# 1. choose the target: <name prefix>|<service uuid>|<characteristic uuid>
curl -s -X POST "$BASE/phone/ble-enqueue?token=$T" \
  -d '{"cmd":"config:MyDevice|0000ffe0-0000-1000-8000-00805f9b34fb|0000ffe1-0000-1000-8000-00805f9b34fb"}'
# 2. check that it connects
curl -s -X POST "$BASE/phone/ble-enqueue?token=$T" -d '{"cmd":"probe"}'
# 3. write bytes (hex), or repeat them: rep:<hex>:<count>:<interval ms>
curl -s -X POST "$BASE/phone/ble-enqueue?token=$T" -d '{"cmd":"aa5501ff"}'
curl -s -X POST "$BASE/phone/ble-enqueue?token=$T" -d '{"cmd":"rep:aa5501ff:60:250"}'
# 4. release the device
curl -s -X POST "$BASE/phone/ble-enqueue?token=$T" -d '{"cmd":"off"}'
```

Results come back through `/phone/control-last-result`. The link is released automatically after
90 seconds without a new command. Bluetooth permissions must be granted on the phone, and you are
responsible for what you connect to: only control devices that belong to you or whose owner agreed.
