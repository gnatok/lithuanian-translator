# Conversation build: device test

1. Install the phone APK. If also testing the watch, install both APKs from the same workflow artifact so signatures match.
2. Complete Setup: Android permissions, Meta registration, glasses permissions, speech/translation/pause-detection downloads, and an installed offline English voice. Keep the screen open during large downloads.
3. Open Start translation. Enable glasses, select the Bluetooth output and wait for the ready state. The app must stay visible. Tap the glasses touchpad to start listening. Experimental Inputs requires Meta capability access; developer mode alone is not proof of availability. The report records activation failures and received input events. If Inputs is unavailable, use **Start from phone · without glasses taps** to test the automatic translation loop independently.
4. Say a short Lithuanian sentence. Stop for three seconds. Recognition and translation take additional time after that silence interval. Expect English text on the phone and English audio through the selected glasses, followed by listening again.
5. Repeat a second sentence to verify the capture capability can restart in the retained session. Test silence alone: it must be discarded without translation.
6. Tap the glasses during playback to pause. Tap again to listen. New speech does not yet interrupt playback automatically: capture is paused during recognition/translation/playback.
7. Disconnect glasses or leave the screen. Expect explicit stop/error rather than ongoing capture. Opening Debug also stops the session.
8. In Debug, export the report and attach it with the step that failed and what you actually heard. Raw audio and conversation text are not logged; error messages may contain SDK details.

This is a build-verified experimental flow, not a claim of verified HSTN behavior. Frame touch cannot currently launch this app from a stopped state; first enable an active session on the phone. Utterances remain bounded by the existing 20-second recording limit.
