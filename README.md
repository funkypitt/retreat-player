# Retreat Player

An Android app that plays MP3s of dhamma talks, bells, and chanting during a
meditation retreat — built on the same principle as its sibling
[Retreat Timer](https://github.com/funkypitt/retreat-timer): **once playback
starts, nothing interrupts it.**

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

## Using it

1. Tap **+** and choose where to load recordings from:
   - **Files on this phone** — pick one or several audio files,
   - **Shared kDrive folder** — an Infomaniak public share link
     (`https://kdrive.infomaniak.com/app/share/…`) — read-only, no password,
   - **Podcast feed** — any RSS feed with audio enclosures.
   The share link and feed URLs are remembered and pre-filled next time.
2. Tick the recordings you want, press **Load**. Each one is stored locally.
3. New tiles land under **Just loaded**; file each into **Bells & chanting**
   (kept on top — used every day) or **Dharma talks** with the ⋮ menu, and
   order them with the ▲ ▼ arrows.
4. Tap a tile to play. The bottom player shows **elapsed and remaining time
   side by side**, with play/pause, stop, ±10 s, and back-to-beginning.

## Build

```
./gradlew assembleDebug
```

No ads, no tracking, no accounts. Network is used only while loading
recordings; playback is fully offline.
