# Zontes dashcam (DVR) — how the official app reads it

Source: Zontes Smart `tayo.com.ZontesIntelligence` v1.12 (3034), decompiled with jadx on 2026-11-06.
Not yet confirmed on a real bike (no traffic capture).

## Link
- The DVR is its own Wi-Fi SoftAP. The SSID starts with `zt` (`DVRActivity.v0`). SSID and password come
  from a QR code (`tpqrcode`: `ssid=`, `pwd=`, `mac=`, `name=`, `machinetype=`, `bm=` BLE MAC).
- Models whose `machinetype` contains `JD_` use Wi-Fi Direct (P2P) instead, matched on `wifimac` / `ssid`.
- BLE (service `fd00`, characteristics `fd01`–`fd03`) is used alongside the Wi-Fi link.
- DVR address: **192.168.3.1**.

## HTTP API — `POST http://192.168.3.1:7686` (class `a.cb3`)
- Body is JSON with `Content-Type: application/json; charset=utf-8`. Timeout is 3 s.

| METHOD | Extra fields | Reply |
|---|---|---|
| `VIDEO.DATE` | `previewType` | array of dates |
| `VIDEO.TIME` | `date`, `previewType` | times for that date |
| `VIDEO.INFO.LIST` | `date`, `time`, `index:0`, `num:120`, `previewType`, `sort:"des"` | `videoBeanList[]`: `playUrl`, `thumbUrl`, `title`, `name` |
| `PICTURE.INFO.LIST` | `index:0`, `num:100`, `previewType:"all"`, `sort:"des"` | `pictureBeanList[]`: `pictureUrl` |
| `LOG.FILE.LIST` | — | `logList[]`: `fileUrl` (relative path) |

- `previewType` is `front` or `rear`, one per camera tab (`a.iu2` / `a.pa7`).
- URLs can come back without a scheme (`192.168.3.1:7686/...`). The app prefixes `http://`.
- Clips are played with ijkplayer straight from `playUrl`. A download is a plain HTTP GET of the same URL
  into `getExternalFilesDir(MOVIES)`.

## Control socket — TCP `192.168.3.1:8081` (class `a.ap8`)
- Binary replies. The type is in byte 2:
  - `1`: ok
  - `2`: bytes 3–4 hold a status, where 500 means an error
  - `4`
  - `6`: byte 3 holds a value
- The commands sent on this socket are not mapped yet.

## File names
- `SimpleDetailActivityMode` splits the clip name on `_`: `<date>_<HH-MM-SS>_…`, for example `2026-11-06_14-35-12_…`.
- `Dashcam.takenAt` reads this pattern.

## Live view (open question)
- The rider says Zontes Smart shows live video when the bike is parked.
- The native code has no live player:
  - The only `RtspMediaSource` reference is a timeout constant.
  - ijkplayer/GSY plays only `VIDEO.INFO.LIST` clips.
- So live view is probably either in the H5 pages (`m.zontes.com/BoxApp`, login required, calling `gotoDvr` / `gotoImage`) or a cloud/T-box stream.
- Next step: run a PCAPdroid capture while live view is open in Zontes Smart, plus a run of our "Live view check".
