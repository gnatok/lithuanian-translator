# Spoken conversation plan

## Requirements

1. Native speaker speaks Lithuanian → English on phone/watch and optional English audio to glasses.
2. Wearer speaks English → stable large Lithuanian reply on phone.
3. Lithuanian audio to glasses is low priority; camera OCR is deferred.
4. Both core directions work without internet after setup. Online operation uses the same local pipeline.
5. Explicit turn controls, no silent language/microphone switching, preserve final output during processing, pause capture during playback.

## Sequence

Update: 0.2 implements DAT PCM integration, manual bounded turns, Parakeet model installation/recognition, phone baseline and WAV import. Physical-device verification is still pending. Silero VAD, TTS, watch turn controls and performance optimization remain future work.

- **0.1 (this PR):** native projects, real typed offline translation, Bluetooth route/level diagnostic, watch result display, CI APKs.
- **0.2:** Meta DAT 1.0 experimental PCM capture and permission/registration flow. Compare HFP and DAT with a partner at conversation distance. Confirm HSTN firmware and offline cold-start behavior.
- **0.3:** Parakeet TDT 0.6B V3 INT8 via sherpa-onnx, Silero VAD, bounded utterance buffers and explicit integrity-checked model installation. Parakeet recognizes speech; ML Kit translates the transcript. Measure memory and thermal behavior.
- **0.4:** offline English TTS to verified glasses output; audio focus, interruptions, acknowledged watch turn controls and microphone foreground service. Respect Android background microphone-start restrictions.
- **0.5:** evaluate real Lithuanian conversations for errors, latency and battery use. Optimize/change models based on measured results.

## Feasibility gates

Developer Mode supports experimentation; it does not imply every permission/firmware restriction disappears. Experimental DAT PCM accompanies camera streaming and needs camera/audio permissions. Discard video for speech use. Registration may need internet; measure offline runtime separately.

HFP may suppress the other speaker. The initial diagnostic confirms transport and level, not intelligibility. Recording/export requires a separate explicit implementation. Assess Lithuanian transcription and reply quality with a native speaker before committing to latency/quality targets.

Pin and integrity-check model downloads, bound audio buffers, cancel stale turns, and handle disconnections/OOM without replacing the last final translation. Keep inference and downloaded models on the phone; watch is a display/controller.

## Starting versions and sources

- Implemented: AGP 8.11.1, Gradle 8.13, JDK 17, compile SDK 36, ML Kit translation 17.0.3.
- Planned: Meta DAT 1.0.0, sherpa-onnx 1.13.8. Standard sherpa AAR SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.
- [Meta getting started](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit)
- [Meta Android toolkit](https://github.com/facebook/meta-wearables-dat-android)
- [Meta experimental PCM guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/audio-streaming/SKILL.md)
- [ML Kit Android translation](https://developers.google.com/ml-kit/language/translation/android)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)

Checked 2026-10-06. Third-party models/SDKs retain their own licenses and terms.
