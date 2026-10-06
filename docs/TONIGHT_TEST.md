# First evening device test

This is a test sequence, not a claim that the target devices have passed. Start with the phone baseline so glasses integration and recognition quality can be assessed separately. Keep the phone app visible for speech and watch controls; background listening is not part of this build.

## 1. Install and download once

1. Download `phone-and-watch-debug-apks` from the same successful GitHub Actions run. Install the phone APK on the Fold and the wear APK on the Watch Ultra. Both devices must use the same build/signing identity for watch communication.
2. Open the phone app → **Offline setup & glasses checks** → **Download translation language pack** while on Wi-Fi.
3. Open **Open speech setup & glasses connection** → **Download Parakeet on Wi-Fi**. Allow about 670 MB plus the small speech-detection model; keep this screen open until completion. If the speech pack is already installed, use **Download / repair pause detection only** for automatic finishing.
4. Install an offline English voice in Android text-to-speech settings if one is not already available. Pair the HSTN and confirm its media-audio connection. Enable Developer Mode in the Meta AI app.

If a download fails, record the exact message and retry on unmetered Wi-Fi. Do not reinstall the app just to retry; uninstalling removes its downloaded models.

## 2. Establish the phone baseline

1. Choose **Listen · Lithuanian → English** on the conversation screen, then **Listen to Lithuanian**.
2. Select **Phone microphone · comparison test**. Turn automatic finishing off for the first attempt.
3. Tap **Start turn**, have a Lithuanian speaker say one short sentence, then **Finish and translate**. Accept microphone permission and tap Start again if requested.
4. Inspect **Latest Lithuanian input** and the completed English translation independently. A wrong transcript is a recognition/input issue; a correct transcript with a wrong result points to translation.
5. Repeat the same sentence twice. Record the first and later turn delays separately.
6. Return to the conversation screen, choose **Reply · English → Lithuanian**, and speak a short English reply. Confirm large Lithuanian output. Try **A+**, **Show full screen**, and **Flip for the person opposite**.

Useful baseline phrases: ask where the nearest supermarket is, ask whether someone speaks English, and ask the price of an item. Include one sentence with a number, time or place name. Ask the Lithuanian speaker to judge meaning, not exact wording.

## 3. Test HSTN capture

1. On the speech screen, tap **Connect app to Meta AI**, complete registration, then **Grant glasses camera + microphone access**. Return to the translator after the permission flow.
2. Select **Glasses · Meta PCM** explicitly. The selected input persists, including phone baseline, so check it before each comparison.
3. Start a Lithuanian turn with your partner at normal conversation distance. Camera streaming is activated for experimental PCM; the app discards video.
4. Repeat the same sentence used for the phone baseline. Compare transcript accuracy, delay and whether the other speaker is audible.
5. Try your English reply through the glasses too. If glasses capture fails, save the exact error; a manual phone-baseline test remains available, but the app should not silently substitute it.

Optional: the main screen's Bluetooth microphone check is a separate HFP diagnostic. Its route/level result does not prove Meta PCM capture works.

## 4. Automatic finishing and playback

1. Enable **Finish automatically after a pause** before starting a new turn. Say a sentence, then stay silent for about one second. Expect capture to end and translation to begin. If the pause detector is not installed, use manual Finish.
2. Try a short natural pause within a sentence. Note premature endings, missed endings and behavior with nearby background speech. Turns are bounded to 20 seconds of captured audio.
3. Produce an English translation, then choose **Play English in glasses** on the phone and select HSTN. Confirm audio physically comes from the glasses. Playback requires an installed offline English voice.
4. Check **Stop playback**. Start another speech turn and confirm playback has stopped. With a harmless test sentence, disconnect the glasses during playback and note where audio stops or is heard; Android route changes need physical validation.

Lithuanian voice playback is not required for this milestone; the Lithuanian reply is shown on the phone.

## 5. Watch controls

1. Keep the phone translator open and enable watch controls under **Offline setup & glasses checks** (or on the speech screen).
2. Open the watch app and confirm a live phone state, not merely a cached completed translation. Try the Lithuanian listening action, finish a turn, then an English reply. Confirm the command is acknowledged and the final result appears.
3. During capture, cancel from the watch and confirm no canceled result replaces the previous completed translation.
4. For watch playback, first select and successfully use the glasses output from the phone. Then test playback of a completed English result from the watch.
5. Leave the phone app or disconnect phone/watch communication. Confirm controls become unavailable or show an error, and the last completed result remains identifiable as an older result. Reopen the phone and re-enable watch controls if needed.

Watch requests use the input selected on the phone speech screen; they do not independently grant permissions or enable background microphone capture.

## 6. Offline and interruption checks

1. After successful online setup, disable Wi-Fi and mobile data while leaving Bluetooth on. Repeat a phone-baseline turn in each direction, then a glasses turn and English playback.
2. Close and reopen the translator while still offline and repeat. Report whether Meta registration/session behavior differs from phone recognition and translation.
3. With a completed result displayed, fold/unfold and rotate the phone. Check direction, text size, completed text and full-screen readability. Repeat while a new turn is being processed; the previous final result should not be replaced by a canceled result.
4. Cancel during recognition; then start a fresh short turn. Native recognition may need time to finish cleanup, but the canceled result must not appear as the new answer.
5. Disconnect and reconnect glasses, then retry deliberately. Check for a clear failure and no unexpected microphone substitution.

## What to send back

Record the build/run number, phone/watch Android versions and glasses firmware. For each failure, send the selected source and direction, exact on-screen message, what you did immediately beforehand, and whether internet was enabled. For recognition quality, include the intended sentence, displayed transcript and translation. Note rough first-turn versus later-turn delay, heating, and any unexpectedly audible phone playback.

The most valuable first results are: phone LT → EN accuracy, HSTN partner capture, EN → large Lithuanian reply, and offline repeatability. Watch and pause-detection polish can be assessed after those core paths.
