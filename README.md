# Lithuanian ↔ English

Android phone and Wear OS companion for conversations in Lithuania using Oakley Meta HSTN. **Lithuanian speech → English is the primary core feature; English speech → large Lithuanian text is also core.**

## Current milestone: 0.1 foundation

Implemented:
- On-device **typed** Lithuanian ↔ English translation using ML Kit. Download the language pack explicitly over Wi-Fi, then translate offline.
- Adjustable 24–80 sp output, 180° flip, retained final output during processing and explicit direction switch. Fold/rotation retains the current view; saved state restores text after activity recreation.
- Foreground, 15-second Bluetooth microphone diagnostic: selected headset, actual input transport/name and measured level. Audio is discarded; capture stops when the app leaves the foreground. A phone-microphone fallback stops the test.
- Wear OS displays the last completed translation with its timestamp, including cached results.
- CI builds both debug APKs, runs unit tests and Android lint.

**Not implemented yet:** speech recognition, Meta DAT PCM capture, speech playback, watch recording controls or model benchmarks. This milestone does not yet translate spoken conversations. HFP level measurements do not establish intelligibility.

## Install

Open [Actions](https://github.com/gnatok/lithuanian-translator/actions), select a successful **Android POC** run and download `phone-and-watch-debug-apks`. Install `phone-debug.apk` on the phone and `wear-debug.apk` on the paired watch using Android Studio or ADB. Install both from the **same run**: Data Layer requires matching application IDs and signing certificates. CI debug certificates may change between runs; uninstall older builds if Android rejects an update (this removes downloaded models and app state).

1. Download the offline language pack over Wi-Fi on the phone.
2. Type Lithuanian, translate, and check English output on phone/watch.
3. Switch direction, type English, and show the large Lithuanian reply.
4. Enable airplane mode, re-enable Bluetooth, restart and repeat both directions.
5. Pair HSTN, grant permissions, select the glasses and test the microphone. See [hardware tests](docs/HARDWARE_TEST.md).

The watch needs its normal companion pairing and Google Play services. Wear OS Data Layer transports results separately from local translation. There is no cloud translation service. Local inference does not imply that third-party ML Kit/Play services SDKs emit no operational telemetry.

## Local build

Install JDK 17 and Android SDK platform 36/build tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir=...`. The wrapper downloads pinned Gradle 8.13 with SHA-256 verification.

```sh
./gradlew :core:test :phone:lintDebug :wear:lintDebug :phone:assembleDebug :wear:assembleDebug
adb install phone/build/outputs/apk/debug/phone-debug.apk
```

Do not commit models, account tokens, recordings or production signing keys.

See [implementation plan](docs/PLAN.md). Target devices: Galaxy Z Fold8, Galaxy Watch Ultra2, Oakley Meta HSTN. Physical compatibility and performance are unverified.
