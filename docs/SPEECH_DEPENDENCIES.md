# Speech dependencies and limits

## Runtime

sherpa-onnx 1.13.8 Android AAR, fetched from the project's GitHub release and SHA-256 verified by `scripts/fetch-sherpa.sh`. Upstream [repository/license](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8). Only ARM64 is packaged in this POC.

Meta DAT 1.0.0 core/camera come from Maven Central. Experimental PCM is coupled to camera streaming. This build requests 16 kHz mono PCM with low-resolution compressed video at 2 FPS, discards all video, and retains at most 20 seconds of audio in memory. Validate this configuration on HSTN; there is no silent HFP/phone fallback.

## Model

Source: [sherpa Parakeet TDT 0.6B V3 INT8 conversion](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78).

Immutable revision: `2bda32ec70b097a55adaa07d9a7173915b43cc78`. The downloader pins individual byte counts and SHA-256 hashes for encoder, decoder, joiner and tokens. Hashes are checked before atomic promotion from `.part`; installed files are verified before first use in a process and after file size/modification identity changes. Model storage is private and excluded from Android backup. A canceled incomplete file is deleted. There are no model uploads.

Original model: [NVIDIA Parakeet TDT 0.6B V3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3). Review upstream model terms; repository MIT licensing does not relicense third-party models or SDKs.

## Current limits

- Recognition infers spoken language; the app's chosen direction determines the translation source/target. It does not promise forced-language Parakeet decoding. Speak the selected source language.
- Manual Finish or optional Silero endpointing (250 ms speech, one second silence). No simultaneous capture/playback.
- One validated recognizer is cached per process, revalidated after private-file identity changes and released on memory pressure. Device latency, thermal effects, memory peaks and Lithuanian quality are unmeasured. Native OOM may terminate the process despite managed error handling.
- WAV parser accepts only RIFF PCM16 mono 16 kHz, 0.25–20 seconds, and bounds container/chunk allocations. Convert other formats externally before import.
- Unit tests validate PCM boundaries, turn cap, timestamps, malformed/truncated WAVs and stale-result gating. They do not validate native recognition accuracy or DAT hardware delivery.
- No background capture. Offline English TTS and explicitly armed foreground watch controls are implemented; physical behavior remains unverified.

## Silero and English voice

Silero model: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`, 643854 bytes, SHA-256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. The mutable upstream release URL is protected by this fixed hash; changed content fails closed. See SILERO_LICENSE.txt.

English synthesis uses a locally installed Android TTS voice whose network-required flag is false. Synthesized output uses a temporary cache file and bounded PCM memory; the file is deleted after completion/cancellation. EnglishPlayback verifies selected Bluetooth routing before sending speech frames and stops on focus/route loss. No guarantee of zero OS reroute latency is made.
