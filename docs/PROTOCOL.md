# Protocol and internals

## Project structure

```text
app/src/main/java/it/hero3/remote/
  CameraApi.java          Legacy HTTP commands, status and media index parsing
  MainActivity.java       Gallery, authentication, controls and downloads
  PlayerActivity.java     Native VLC player and lifecycle management
  PlaybackStatus.java     Buffering state and stalled-video detection
  PreviewUi.java          Shared photo/video preview layout
  PreviewCache.java       Bounded disk preview cache
  DownloadTransfer.java   Streaming transfer, progress and completeness checks
  CrashDiagnostics.java   On-demand error/process-exit report sharing
app/src/test/             Protocol, parsing, buffering, cache and transfer tests
```

## Legacy endpoints

The app talks to the camera's legacy addresses:

| Purpose | Endpoint |
| --- | --- |
| Shutter | `http://10.5.5.9/bacpac/SH?t=PASSWORD&p=%01` / `%00` |
| Video / Photo mode | `http://10.5.5.9/camera/CM?t=PASSWORD&p=%00` / `%01` |
| Preview activation | `http://10.5.5.9/camera/PV?t=PASSWORD&p=%02` |
| Status | `/camera/sx?t=PASSWORD`, with `/camera/se` battery fallback |
| Media | `http://10.5.5.9:8080/videos/`, fallback `/DCIM/` |
| Live HLS | `http://10.5.5.9:8080/live/amba.m3u8` |

`PASSWORD` is a placeholder. The `p` command parameter is a percent-encoded
binary byte. The media index is read from the camera's Cherokee HTML output.
Protocol references: [GoPro Wi-Fi Hack HERO3 documentation](https://github.com/KonradIT/goprowifihack/tree/master/HERO3).

## Implementation notes

- Password is kept in memory only, not saved to preferences or files. Traffic
  goes over the available Wi-Fi network, not mobile data.
- Downloads are streamed, so a whole video does not need to fit in RAM. The
  screen stays awake during a transfer.
- Recorded-video network buffering is configured to 500 ms; live buffering
  uses 1500 ms. These values do not represent a guaranteed startup delay.
- The UI shows buffering when reported by VLC or when displayed frames stop
  advancing; it does not show a permanent "Playing" label.
- Live playback has no artificial 8 fps conversion limit. The native VLC
  player avoids browser MediaSource limitations but cannot repair a missing
  or stalled camera stream.
- A recording continues when you open or close its live preview.
- Dates come from the camera's HTML directory index, not from file names or
  EXIF. Unknown dates sort last.
- Preview cache is bounded to 12 MiB in RAM and 64 MiB on disk. Android may
  clear its cache. Files whose modification date changes get a new cache key.
  If the camera omits dates, replacing a file at the same path can leave an
  old preview until the app cache is cleared.
- Battery from `sx` is shown as the firmware's raw level. If the response is
  short, `/camera/se` is tried: when it returns a compatible legacy status,
  the percentage is read at byte 20 (1-based). The extra `se` attempt runs at
  most once per minute per session; the last valid reading is kept and the
  tooltip shows when it was received. If neither endpoint reports it, the UI
  shows "not reported by firmware" without inventing a value.
- Diagnostics (long-press Wi-Fi icon) can include the last Java exception,
  device info and, on Android 11+, process-exit info. Native libVLC crashes
  cannot be caught by Java exception handling. Review a report before posting:
  traces may contain local paths or URLs.
