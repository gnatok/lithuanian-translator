# Lithuanian ↔ English

Android phone and Wear OS companion for conversations in Lithuania using Oakley Meta HSTN. **Lithuanian speech → English is the primary core feature; English speech → large Lithuanian text is also core.**

## Current milestone: 0.4 foreground conversation POC

Start with **Setup**, then **Start translation**. Setup checks Android/Meta permissions, required offline models and an installed offline English voice. Enable the glasses session and tap the touchpad to listen. After three seconds of silence, recognition and translation run locally, English plays through the selected glasses output, and listening restarts. Experimental Inputs must be available; **Start from phone · without glasses taps** tests the same audio loop independently.

The app must remain visible. Frame touch does not cold-launch it. Capture pauses during recognition and playback; automatic interruption by new speech is not implemented. A glasses tap (when enabled) or phone pause stops the current turn. Physical HSTN behavior is unverified.

Use the [conversation test guide](docs/CONVERSATION_TEST.md). The separate [debug timeline and export](docs/DEBUGGING.md) records permissions, session/input events, audio levels, speech detection, model operations, timings, translation and playback errors without saving audio or conversation text.

Implemented:
- On-device **typed** Lithuanian ↔ English translation using ML Kit. Download the language pack explicitly over Wi-Fi, then translate offline.
- Adjustable 24–80 sp output, 180° flip, retained final output during processing and explicit direction switch. Fold/rotation retains the current view; saved state restores text after activity recreation.
- Foreground, 15-second Bluetooth microphone diagnostic: selected headset, actual input transport/name and measured level. Audio is discarded; capture stops when the app leaves the foreground. A phone-microphone fallback stops the test.
- Wear OS displays the last completed translation with its timestamp, including cached results.
- CI builds both debug APKs, runs unit tests and Android lint.
- Parakeet V3 INT8 recognition on the phone, connected to both translation directions. Explicit, integrity-checked Wi-Fi model installation (about 670 MB); models are not bundled in the APK.
- Experimental Meta DAT 1.0 PCM capture with registration and camera/microphone permission flow. Camera streaming is active for PCM; video is discarded.
- Explicit phone-microphone baseline and WAV import (16 kHz mono PCM16, 0.25–20 seconds). Manual Finish, 20-second audio cap, interruption/cancellation handling and no saved audio.

New in 0.3:
- Clear Listen/Reply phone controls, visible source transcript, foldable split layout and full-screen Lithuanian presentation.
- Silero pause detection: ≥250 ms of speech followed by three seconds of silence ends a turn. Manual Finish remains available in the comparison screen.
- Offline English playback to a selected Bluetooth media output, with route verification, interruption handling and no intentional phone-speaker fallback.
- Watch turn/finish/cancel/playback commands with correlated acknowledgments, expiry, duplicate protection and explicit phone arming. The phone must remain foreground; no background listening.
- Cached Parakeet engine across turns, serialized native access and memory-pressure cleanup.

**Still pending:** physical-device model/firmware/accuracy benchmarks, background capture, optional Lithuanian speech playback and camera OCR. Recognition and experimental DAT integration have not been executed on the target devices. HFP remains diagnostic-only.

Use [tonight's test guide](docs/TONIGHT_TEST.md) for the shortest useful device session. [Architecture notes](docs/ARCHITECTURE.md) document resource ownership and runtime limits.

### Try speech

Complete **Setup** over unmetered Wi-Fi and keep that screen open. Completed verified files survive cancellation; incomplete files restart. Allow roughly 1 GB of free storage. The phone APK targets ARM64. Manual reply/comparison tools remain available in the expandable home section.

For glasses, enable Developer Mode in Meta AI, connect this app to Meta AI, grant glasses camera/microphone access and tap **Start glasses turn**. Registration needs internet; runtime offline behavior depends on SDK/firmware and must be tested. This development build uses Meta application ID/token `0` and callback scheme `lttranslator`. If Developer Mode still requires project configuration for your account, use your Wearables Developer Center settings; do not commit credentials.

For a test without glasses, explicitly select **Phone microphone • comparison test** or import a WAV. Tap **Finish and translate**; the recognized transcript returns to the main screen and feeds the selected translation direction. Leaving the speech screen cancels capture/download/recognition results. Model hashes are verified on first use or file change; one recognizer stays cached while memory permits. Native inference cannot be interrupted midway; canceled results are discarded and subsequent model work waits for it to finish.

The speech download also installs Silero (~644 KB). Existing 0.2 installs can use **Download / repair pause detection only**. Disable automatic finishing for manual comparisons. For English playback, install an offline Android English voice and select the glasses output on the phone before using the watch Play control. Android audio routes are asynchronous; actual disconnect behavior must be checked on your devices.

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

Before the first build, run `bash scripts/fetch-sherpa.sh` (WSL/Git Bash on Windows). It fetches and verifies the pinned sherpa-onnx AAR. See [speech dependencies](docs/SPEECH_DEPENDENCIES.md).

See [implementation plan](docs/PLAN.md). Target devices: Galaxy Z Fold8, Galaxy Watch Ultra2, Oakley Meta HSTN. Physical compatibility and performance are unverified.
