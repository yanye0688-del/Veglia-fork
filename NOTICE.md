# Notice

This project is a derivative of **Veglia** by Evelyn & River
(<https://github.com/sebastianevan200-stack/veglia>).

- **Base version:** upstream commit `bc0e4dacd5411f12e742fed276af28f6cf94487f`
  (dated 2026-07-13), obtained and first deployed on 2026-07-13.
- **License of that base version:** MIT, © 2026 Evelyn & River. The upstream
  repository states that copies obtained before 2026-08-03 keep the MIT terms,
  and later releases are under a different license. Only the 2026-07-13 base is
  used here; nothing was taken from later upstream releases.
- The original copyright notice is kept in [LICENSE](LICENSE).

## What was changed

On the server (`server/veglia_server.py`):

- shell-command control endpoints (`/phone/control-enqueue`, `/phone/control-result`,
  `/phone/control-last-result`)
- alarm, generic BLE-write and audio-playback endpoints
- a generic file-upload endpoint (`/phone/upload`) and a written recipe for reading a watch's data (heart rate, steps and more) through Gadgetbridge (`docs/heart-rate.md`)
- app-switch events with debouncing and noise filtering (the base version had removed
  foreground-app reporting; this fork adds it back, so say so to the person whose phone it is)

On the Android app:

- Shizuku shell bridge with a status/permission button on the main screen
- boot/update receiver that restarts the service and respects an explicit Stop
- alarm setting under the app's own identity
- foreground-app reporting
- a generic BLE write bridge (`BleBridge`) whose target device is configured at runtime
- native audio playback with optional earpiece routing
- a floating caption overlay and a caption-notification fallback

Modifications are © the contributors of this fork and released under the same MIT license.
The original copyright notice of Evelyn & River is kept in LICENSE.

Server hardening in this fork: constant-time token comparison, the token is redacted
from logs, and the server refuses empty, placeholder or short tokens.
