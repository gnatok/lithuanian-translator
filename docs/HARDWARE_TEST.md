# First hardware session

Record commit/build run, OS versions, glasses firmware and connection state. No physical-device tests have been performed remotely.

## Translation

- Download pack online. Try `Laba diena. Kur yra autobusų stotis?` in LT → EN and `Could you speak more slowly, please?` in EN → LT. Have a Lithuanian speaker judge the reply.
- Restart in airplane mode. Both typed directions must translate. Re-enable Bluetooth separately for watch sync.
- Rapidly submit phrases and change direction while processing. Old results must not replace the current turn.
- Fold/unfold and rotate with output visible. Check font size, flip, scrolling, keyboard and system-bar clearance.
- Disconnect watch; phone translation must remain usable. Cached watch output must show its timestamp.

## HSTN microphone diagnostic

- Pair HSTN, select it explicitly, confirm actual input is Bluetooth.
- Test wearer speech, then partner speech at one metre. Note levels. Audio is discarded and no transcript is generated; a strong meter does not prove intelligibility.
- Disconnect glasses during recording. Test must stop rather than continue with phone audio. Up to two seconds are allowed for initial route negotiation; that audio is discarded too.
- Stop manually, navigate away, deny permissions. Recording must end when leaving the app and errors must be visible.
- If partner speech is suppressed, prioritize DAT PCM comparison.

## Later spoken POC acceptance

The 0.2 speech path is ready for these tests after a successful build:

1. Download both packs online, then use a short English WAV/phone-mic turn. Check the displayed transcript before assessing translation.
2. Repeat with Lithuanian speech, including `Laba diena. Kur yra autobusų stotis?`. Test both directions after restarting in airplane mode.
3. Register with Meta AI online in Developer Mode; grant camera/microphone access. Test a glasses turn with wearer speech, then the other speaker at one metre. Confirm the glasses recording indicator. Video is discarded.
4. Compare DAT transcription against the phone-mic baseline. This build does not yet transcribe HFP.
5. Test Finish, Cancel, 20-second limit, glasses disconnect, backgrounding and permission denial. No partial/old turn should replace the final translation. A missing PCM stream must report a timeout.
6. Import wrong-rate, stereo and truncated WAV files: clear errors, no inference. Cancel a download and restart it: verified complete files are reused.

Both speech directions must work from glasses through local recognition and translation in airplane mode after setup. Measure time to final result, transcription/translation errors, RAM and thermal behavior in quiet/noisy samples. Preserve final output on interruption. English playback must verify glasses routing and pause capture. Lithuanian playback is optional.
