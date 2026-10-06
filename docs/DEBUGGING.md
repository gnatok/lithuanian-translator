# Device debugging

Open **Debug timeline / export report**, tap **Mark test start**, return to the conversation and reproduce the problem. Open Debug again, tap **Export**, save the text file, and attach it with a short description of what you expected and heard. Opening Debug stops active capture; export after reproducing the problem.

The report includes build/device details, permissions, memory/storage, network state, audio routes, session/active-turn IDs, timestamps, Meta registration/session events, PCM arrival and levels, speech detection and finish reasons, model downloads/loading, recognition and translation durations, playback routing/errors, and phone-side watch commands/acknowledgements. It records character counts rather than conversation text and never stores recorded audio. SDK exception messages and stack traces are included; review reports before sharing.

Logs remain in private app storage across restarts, rotating through approximately 3 MB. Clear removes local history. Export is manual; nothing is uploaded automatically. Native process kills may not produce a Java crash event.

The conversation screen adds an experimental foreground loop: armed glasses touch → Lithuanian capture → three seconds of silence → local recognition/translation → English playback → next capture. Inputs must be available for the app and the glasses; frame-touch activation is not a cold launch. Capture pauses during recognition and playback, so interruption by new speech is not yet supported. Hardware behavior still needs verification.
