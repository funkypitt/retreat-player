# Retreat Player — notes

Reference material moved out of the README.

## Why it's reliable

- Every recording is **downloaded into app-private storage when it is loaded**,
  so playback needs no network — airplane mode is fine.
- Playback runs in a **foreground service** (type `mediaPlayback`) whose
  MediaPlayer holds a **partial wake lock**: the OS cannot kill or pause it,
  even with the screen off for an hour-long talk.
- The app takes **no audio focus**, so nothing else on the phone can duck or
  pause a talk.
- While a recording plays, the screen is kept on (`FLAG_KEEP_SCREEN_ON`) — no
  screen lock, no screensaver, exactly as if a video were playing — showing the
  progress the whole time.
- A recording plays **only because you pressed play**, and only once: when it
  ends, nothing else starts automatically.
