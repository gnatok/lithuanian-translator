# Conversation architecture

## Responsibilities

The phone owns audio capture, speech recognition, translation, model storage and playback. The watch displays completed translations and sends explicit turn commands. Glasses provide experimental PCM input and a user-selected Bluetooth playback destination. No inference runs on the glasses or watch.

Lithuanian speech → English is the primary listening flow. English speech → large Lithuanian text is the reply flow. Direction is explicit and fixed for a turn; the app does not guess the desired translation direction. Lithuanian playback and camera OCR remain outside this milestone.

`MainActivity` owns conversation direction, typed translation, the last completed result and presentation controls. `SpeechActivity` owns a foreground capture/recognition session. `GlassesCapture` adapts Meta DAT camera/PCM streaming. `PcmTurn` owns a bounded 16 kHz mono buffer, rejecting reordered frames and large timing gaps. Phone microphone and strict WAV import are independent baseline inputs, never silent fallbacks for failed glasses capture.

## Turn and resource ownership

A turn starts from an explicit local or accepted watch action. Capture ends manually, at the 20-second audio limit, or after optional Silero speech detection observes speech followed by silence. Capture teardown precedes Parakeet recognition; the transcript then goes to ML Kit translation. Cancellation advances a generation so stale callbacks cannot replace a newer result.

Leaving the speech screen stops capture. This milestone has no background microphone service and no always-listening mode. Watch commands require explicit foreground arming on the phone. A watch request cannot independently start background microphone capture. Commands need an acknowledgement; unavailable or stale sessions must be shown as such on the watch. A cached result remains a completed, timestamped result rather than evidence of a live phone session.

Android microphone recording, DAT collectors and native detectors each have one owner and explicit teardown. Stop cancels collectors before closing the DAT camera/session. Diagnostic `AudioProbe.stopThen` waits for recorder release and completion of the app's route-restoration calls before handing control to capture or playback; subsequent stop/start/close invalidates the continuation.

## Offline models and inference

Parakeet TDT V3 INT8 performs speech recognition; it does not translate. ML Kit performs Lithuanian ↔ English text translation. Silero is an optional endpoint detector, not a replacement recognizer. Downloading models is an explicit setup action over Wi-Fi; capture and inference never silently download missing models. Speech/VAD downloads are pinned by SHA-256 and finalized from temporary files only after validation.

`SpeechModels` serializes installation, recognition and native release through a process-wide coroutine mutex. It verifies model content before first use and again when private-file size/modification identity changes. It caches one recognizer across completed turns, removing repeated model hashing and loading from the normal conversation path. Memory-pressure callbacks queue release through the same mutex. Native decode is not interruptible: cancellation suppresses its result, but the next operation waits for native work and cleanup to finish. Model preparation and decode timing are reported separately.

Audio buffers are transient. The app does not retain conversation recordings. Models live in non-backed-up private storage. The same local inference path operates with and without internet after setup; Meta registration and firmware setup are separate from inference and require physical offline verification.

## Playback and presentation

English playback is an explicit action for a completed English result. `EnglishPlayback` selects an offline Android TTS voice, synthesizes locally and uses an AudioTrack with a selected Bluetooth output. Capture and diagnostics must be stopped before playback begins. Route verification and audio-focus handling stop playback on an invalid route or interruption; no intentional phone-speaker fallback is offered.

Android routes audio asynchronously. Completing route API calls does not prove the physical route has settled, and a disconnect may race route-change delivery. Actual device routing must be checked during playback. This implementation cannot promise zero audible leakage under every OS/device disconnect race; test that behavior on the target phone and glasses before treating output as private.

The last completed translation is independent of recording/loading/error status. It remains visible while a new turn is processed. The phone offers large adjustable text and a full-screen, flippable reply; the watch favors clear direction, recording state, command acknowledgement and readable final output.

## Physical validation boundaries

Builds, lint and unit tests establish software checks, not HSTN compatibility or recognition quality. Tonight's device tests must verify:

- Meta registration callback, permissions, firmware and PCM delivery with the camera active.
- A Lithuanian partner at conversation distance; compare phone baseline and glasses input.
- Offline cold start after setup, both translation directions and installed offline English voice.
- Repeated turns, cancellation during decode, reconnect, fold/rotation and leaving the screen.
- Watch arming, acknowledgement, stale/disconnected controls and preservation of the last result.
- Bluetooth playback, interruption/disconnect behavior and no concurrent capture/playback.
- First versus warm-turn latency, sustained memory, thermal behavior and battery use.

Model accuracy and automatic endpoint timing remain empirical decisions. Keep manual Finish available and permit disabling automatic endpoint detection during comparison.
