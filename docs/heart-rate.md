# Reading a watch's heart rate through Gadgetbridge (optional recipe)

This is **not part of the Veglia app**. It is a recipe built from three things Veglia
already gives you: the Shizuku shell endpoint, the phone's own `curl`, and the generic
`/phone/upload` endpoint. It was written down from how the original maintainers did it with one
Xiaomi band; other watches will differ.

**Consent first.** A heart rate is a piece of someone's body. Set this up only if the person
wearing the watch asked for it, and tell them whenever you look.

## What you need

- [Gadgetbridge](https://gadgetbridge.org/) installed on the phone and paired with the watch.
- In Gadgetbridge, set the database export location to a public folder (for example
  `Download/Gadgetbridge`). The export file is then
  `/storage/emulated/0/Download/Gadgetbridge/Gadgetbridge.db`.
- Veglia's Shizuku bridge working (`/phone/control-enqueue`, see [setup.md](setup.md)).
- A phone shell with `curl` (Android's own is enough).

## The four steps

Replace `YOUR-SERVER` and `TOKEN`. Each command goes through `/phone/control-enqueue`, so the
phone runs it a few seconds to a couple of minutes later (it polls).

1. **Ask Gadgetbridge to fetch fresh data from the watch.**
   `am broadcast -a nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC nodomain.freeyourgadget.gadgetbridge`
2. **Ask it to export its database.**
   `am broadcast -a nodomain.freeyourgadget.gadgetbridge.command.TRIGGER_EXPORT nodomain.freeyourgadget.gadgetbridge`
3. **Have the phone upload the file to your server.** Run it in the background
   (`nohup ... &`), otherwise the shell service may time out the transfer:
   ```
   nohup curl -s -m 170 -F file=@/storage/emulated/0/Download/Gadgetbridge/Gadgetbridge.db \
     'https://YOUR-SERVER/veglia/phone/upload?token=TOKEN&name=gadgetbridge.db' >/dev/null 2>&1 &
   ```
   The server saves it to `data/uploads/gadgetbridge.db` and does not notify anyone.
4. **Read it on your side** (read-only):
   ```python
   import sqlite3
   c = sqlite3.connect("file:data/uploads/gadgetbridge.db?mode=ro", uri=True)
   for ts, hr in c.execute("select TIMESTAMP, HEART_RATE from XIAOMI_ACTIVITY_SAMPLE "
                           "where HEART_RATE > 0 order by TIMESTAMP desc limit 8"):
       print(ts, hr)
   ```

## Things that will bite you

- **The table name depends on the watch.** `XIAOMI_ACTIVITY_SAMPLE` was right for the band this
  was written for. For another model run `select name from sqlite_master` and use the
  non-empty `*_ACTIVITY_SAMPLE` table.
- **Always print how old the newest row is.** If it is more than an hour old, the most likely
  reason is that the watch is off the wrist or not connected, not that your code is broken.
  Ask the person before debugging.
- **Step 1 does not always take effect.** Whether the watch really syncs depends on Bluetooth
  being connected; treat the timestamp as the truth, not the fact that the commands ran.
- If an upload fails, keep the previous copy rather than deleting it first, so you do not go
  blind between attempts.
