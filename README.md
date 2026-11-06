# Firestick AirPlay

A Fire TV app that makes your Firestick show up in your iPhone's **Screen Mirroring**
list, so you can watch apps like Dreaming Spanish on the TV.

While the app is open on the TV, your iPhone (or iPad / Mac) sees it as an AirPlay
receiver. Two ways to use it:

* **AirPlay button in a video app** (e.g. Dreaming Spanish): the phone hands the video
  over to the TV. The TV streams it by itself, so you can lock the phone or use other apps.
* **Screen Mirroring** from Control Center: the TV shows exactly what is on the phone.

---

## 1. Get the app file (APK)

Every time code is pushed, GitHub builds the app automatically.

1. Open this repository on GitHub and go to **Releases** (right-hand side of the main
   page) → **Firestick AirPlay (latest build)**.
2. Download **`FirestickAirPlay.apk`**.

The repository is private, so you have to be signed in to GitHub to download it. The
easiest way is to download it on your phone (Samsung or iPhone) and send it to the TV
as described below.

## 2. Allow the Fire TV to install it (one time)

1. On the Fire TV go to **Settings → My Fire TV → About** and click on the device name
   (e.g. "Fire TV Stick 4K") **7 times** until it says *"You are now a developer"*.
2. Go back to **Settings → My Fire TV → Developer options** and turn on
   **Install unknown apps** (on older models: **Apps from Unknown Sources**).

## 3. Put the APK on the Fire TV

Pick whichever is easiest:

**Option A – "Send Files to TV" (easiest with the Samsung phone)**
1. Install **Send Files to TV** on the Fire TV (Amazon Appstore) and on the Samsung phone
   (Google Play).
2. Open it on both. On the TV choose **Receive**; on the phone choose **Send**, pick
   `FirestickAirPlay.apk` from Downloads, then pick the TV.
3. On the TV, open the received file and choose **Install**.

**Option B – "Downloader" app with a direct link**
1. Install **Downloader** (by AFTVnews) on the Fire TV from the Appstore.
2. Upload `FirestickAirPlay.apk` somewhere that gives a direct download link (Google
   Drive with "anyone with the link", Dropbox, etc.) and type that link into Downloader.
   (If you make this GitHub repository public, the release download link works directly.)
3. When the download finishes, choose **Install**.

The app then appears under **Your Apps & Channels** as **Firestick AirPlay**. You can
move it to the front of the home row so it's easy to reach.

## 4. Watch Dreaming Spanish on the TV

1. Make sure the iPhone and the Fire TV are on the **same Wi-Fi network**.
2. Open **Firestick AirPlay** on the TV. It shows *"Ready — waiting for your iPhone"*.
3. On the iPhone, open Dreaming Spanish and start a video.
4. Tap the **AirPlay button** in the video player (a screen with a triangle) and choose
   the Fire TV (by default it is called "*your Fire TV's name* AirPlay").
5. The video plays on the TV. You can now lock the phone; play/pause and the progress
   bar on the phone still control the TV.

While a handed-over video plays, the Fire TV remote works too:

* **OK** (center button) – subtitles on/off (Spanish if the video has them, otherwise
  English). Your choice is remembered.
* **Play/Pause** – pause or resume.
* **◀ / ▶** – skip back / forward 10 seconds.

Subtitles only appear if the video stream itself carries them. When Dreaming Spanish
plays the next video automatically, that is up to the Dreaming Spanish app on the phone;
the TV plays each new video it is sent.

If a video app's AirPlay hand-over doesn't work (the TV says the video could not be
played), use **Screen Mirroring** instead: swipe down from the top-right corner of the
iPhone to open **Control Center**, tap **Screen Mirroring** and choose the Fire TV. With
mirroring the phone has to stay on and in the video app.

To stop, tap the AirPlay button again and choose **iPhone** (or **Stop Mirroring** in
Control Center), or press **Back** / **Home** on the Fire TV remote.

**Keep the app open on the TV** while you watch. The Fire TV only appears in the iPhone's
list while the app is on screen.

### What about the Samsung phone?

Android phones don't use AirPlay. Fire TV can already show an Android phone's screen
without any extra app:

1. On the Fire TV remote, **hold the Home button** and choose **Mirroring**.
2. On the Samsung phone, swipe down twice and tap **Smart View**, then pick the Fire TV.

## Troubleshooting

| Problem | Try this |
|---------|----------|
| The Fire TV doesn't appear in Screen Mirroring | Check both devices are on the same Wi-Fi (not a "guest" network, and on some routers the same 2.4 GHz/5 GHz band). Close and reopen the app. Turn the iPhone's Wi-Fi off and on. |
| It appears but connecting fails | Restart the Fire TV (Settings → My Fire TV → Restart) and try again. |
| Picture but no sound | Turn the volume up on the iPhone while mirroring – the iPhone's volume buttons control the TV volume. |
| Video is choppy | Move the router closer or use 5 GHz Wi-Fi. |
| "Could not start the AirPlay receiver" | The Fire TV isn't on Wi-Fi/Ethernet. Connect it to the network and reopen the app. |

---

## For developers

### How it works

* `third_party/uxplay/lib` – the AirPlay mirroring server from
  [UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0): mDNS/Bonjour advertisement,
  pairing, FairPlay handshake, RTP mirror/audio streams.
* `third_party/libplist` – Apple property-list library used by UxPlay.
* `app/src/main/cpp/airplay_jni.c` – starts the server and passes decrypted video/audio
  frames to Java.
* `VideoDecoder.java` – H.264 hardware decoding with `MediaCodec`, rendered to a
  `SurfaceView`.
* `AudioPlayer.java` – AAC-ELD / AAC-LC / ALAC decoding with `MediaCodec`, played with
  `AudioTrack`.
* `HlsPlayer.java` – "AirPlay video": plays the stream URL handed over by the iPhone
  with ExoPlayer (UxPlay's `-hls` mode). Videos protected with Apple FairPlay DRM can't
  be played this way.
* `MainActivity.java` – the full-screen TV UI.

Not supported: H.265 mirroring and PIN/password protection.

### Building

The GitHub Actions workflow (`.github/workflows/build.yml`) builds the APK. To build
locally you need the Android SDK, the NDK version in `ndk.version` and JDK 17:

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$(cat ndk.version)
scripts/build-openssl-android.sh     # cross-compiles libcrypto once
./gradlew assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
```

Install with `adb connect <fire-tv-ip>` then `adb install -r app-release.apk` (enable
**ADB debugging** in Developer options first).

The APK is signed with `signing/sideload.keystore` so newer builds install over older
ones. That key only identifies this sideloaded app.

### License

GPL-3.0-or-later (see `LICENSE`), because the app includes UxPlay. libplist is LGPL-2.1;
llhttp is MIT.
