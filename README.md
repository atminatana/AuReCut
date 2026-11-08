# AuReCut

**English** · [Русский](README.ru.md)

![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84)
![No ads](https://img.shields.io/badge/ads-none-success)

AuReCut is a small, free Android app to **record, trim and save audio**. No ads, no accounts, no tracking, and not even an Internet permission: everything happens on your device.

Record from the microphone or open an existing file, pick the part you want, optionally normalize its volume, and save it in the format you need.

## Features

- **Record** from the microphone: choose the audio source (microphone, unprocessed if the device supports it, voice recognition, voice communication), the sample rate (16, 22.05, 44.1 or 48 kHz) and mono or stereo. Recording continues with the screen off or the app in the background.
- **Open** WAV, FLAC, AAC/M4A, Opus/OGG and MP3 files, from the app or with "Open with" in a file manager.
- **Trim** with two markers over a waveform. By default the whole track is selected.
  - Pinch to zoom, drag to scroll, or use the zoom buttons; an overview strip shows where you are.
  - Move the markers with a finger or type exact start, end and length.
  - **Play the selected range** before saving.
- **Normalize** the peak level (default −1 dBFS, editable).
- **Save** as WAV, FLAC, AAC (.m4a) or Opus (.ogg). AAC and Opus have a bitrate choice. You choose the file name and the folder with the system dialog.
- Asks for confirmation before you leave with unsaved work.

### Formats

| Format | Open | Save | Notes |
|---|:---:|:---:|---|
| WAV | yes | yes | 16-bit PCM. Files over 4 GB are refused. |
| FLAC | yes | yes | Lossless, written by the app itself. |
| AAC / M4A | yes | yes | 64, 96, 128 (default), 192, 256 kbps. |
| Opus / OGG | yes | yes | 24, 32, 64, 96 (default), 128, 192 kbps. Opus only supports 8, 12, 16, 24 and 48 kHz, so other rates are converted to 48 kHz and the app tells you so. |
| MP3 | yes | no | Open only. There is no MP3 encoder in the app. |

The saved file keeps the sample rate of the recording or source file (except for the Opus case above). Which formats can be opened and saved also depends on the codecs of your device: a format whose encoder is missing is simply not offered. Files with more than two channels are reduced to the first two.

## Install

Download the signed APK from the [Releases](../../releases) page and open it on your phone. Android will ask you to allow installing from this source the first time.

Requires **Android 10 (API 29) or newer**.

## Languages

English, Russian, Spanish, Portuguese (Brazil), French, German, Simplified Chinese and Japanese. The app follows the system language and falls back to English for any other language. On Android 13+ you can also set the language for AuReCut alone in the system settings.

## Privacy

- No Internet permission, no analytics, no ads.
- Permissions: microphone (to record), foreground service of the microphone type (so a recording survives a locked screen), and notifications (only to show the "recording" notification; recording works without it).
- Recordings and opened files are decoded into the app's private cache and deleted when you leave the editor or when the app starts again.

## Build from source

You need JDK 17 and the Android SDK (platform 35). The easiest way is to open the project in Android Studio. From the command line:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests of the audio core
./gradlew lintDebug              # Android lint
```

The release build is signed only if the signing settings are in the environment (see below); otherwise `assembleRelease` produces an unsigned APK.

### Project layout

```
app/src/main/java/org/aurecut/app/
  core/    pure Kotlin: PCM access, resampler, normalization, peak pyramid, FLAC and WAV writers, time format
  audio/   Android audio: AudioRecord service, decoder, AAC/Opus encoder, exporter, range player
  ui/      Jetpack Compose screens: start, record, editor, save sheet
  AppViewModel.kt, MainActivity.kt, AuReCutApp.kt
app/src/main/res/values*/strings.xml   translations
app/src/test/                          JUnit tests for core/
```

### Adding or fixing a translation

Copy `app/src/main/res/values/strings.xml` to `values-<language>/strings.xml`, translate it, and add the language to `app/src/main/res/xml/locales_config.xml`. Keep the placeholders (`%1$s`, `%d`) as they are. Pull requests are welcome.

## Releases (for maintainers)

GitHub Actions does the work:

- **CI** (`.github/workflows/ci.yml`) runs tests, lint and a debug build on every push to `main` and on every pull request, and keeps the debug APK as a build artifact.
- **Release** (`.github/workflows/release.yml`) runs when you push a tag like `v1.2.3` (or `v1.2.3-rc1` for a pre-release). It builds a signed APK, checks the signature and publishes a GitHub Release with generated notes, the APK and its SHA-256. The version name is the tag without `v`; the version code is `major*10000 + minor*100 + patch`.

One-time setup:

1. Create a keystore and **keep it safe** (every update must be signed with the same key):

   ```bash
   keytool -genkeypair -v -keystore release.jks -alias aurecut \
           -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | output of `base64 -w0 release.jks` |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `aurecut` (or the alias you chose) |
   | `KEY_PASSWORD` | the key password |

3. Release:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

Never commit the keystore; `.gitignore` already excludes `*.jks` and `*.keystore`.

## License

AuReCut is free software under the [GNU General Public License v3.0](LICENSE). You may use, study, share and improve it under the terms of that license.
