# Ajax: handoff

Ajax is a personal, mostly offline voice assistant for Android, built for one person (Franklin, GitHub `sys-kraken`) and their phone. This document is everything a new assistant needs to continue the work without asking them to repeat themselves.

State at handoff (2026-11-10): everything is committed and pushed, CI is green on the last commit `963de54`, and the debug build of that commit is installed on their phone.

---

## 1. Working with Franklin

These are firm rules. Follow them without being reminded.

- **No co-author line in commits.** They said so explicitly. Commit as `git -c user.name=Franklin -c user.email=250218807+sys-kraken@users.noreply.github.com commit ...`.
- **Commit and push only when they say so** ("commit", "commit and push"). Ask with a yes/no when work is ready; never push on your own.
- **Never call something ready or working until it is verified**: unit tests pass, it is installed and checked on the phone where possible, and CI is green after a push. Always say plainly what was not tested.
- **No emoji or symbol glyphs in the app UI** (no check marks, crosses, arrows, kebab dots). Plain words only. Vector icons drawn as lines (like `ic_back`, `ic_mic`) are fine.
- **Short, direct, technical answers.** They read on their phone. Lead with the answer, then the detail.
- **They use the debug build until the app is "final".** Do not install the release build unless they ask.
- **Their phone is in use while you work.** Before driving it over adb (opening the overlay, typing, screenshots), check the focused window. If they are in another app, do not take over the screen; install quietly and let them test.
- **Ask before anything destructive on their phone** (uninstalling, deleting data). Changes to their phone's settings are fine when they ask for them; tell them how to undo them.
- They test by trying things and sending screenshots with the exact phrase they said. Treat the screenshot as the bug report.

---

## 2. The phone

- Samsung Galaxy S22 Ultra, model SM-S908E, One UI 8 / Android 16, region XSG. OLED, so the UI is pure black.
- USB serial `R5CT21TZV2W`. Wireless adb was used at `192.168.101.6:5555` but it drops often; prefer USB. With both connected, set `ANDROID_SERIAL=R5CT21TZV2W` or pass `-s`.
- adb: `C:\Users\sys-admin\ajax-android\sdk\platform-tools\adb.exe`. In Git Bash set `MSYS_NO_PATHCONV=1`, and give `adb install` / `adb push` Windows paths (`C:\\...`), not `/c/...`.
- Bixby's agent was removed for the user with `pm uninstall -k --user 0 com.samsung.android.bixby.agent`. Restore: `adb shell cmd package install-existing --user 0 com.samsung.android.bixby.agent`.
- Ajax is the default digital assistant; the side key (press and hold) opens it. RegiStar's back tap opens `ListenActivity`, which opens the same overlay.

### Granted from the PC (need granting again after any uninstall of Ajax; normal updates keep them)

```
adb shell pm grant com.ajax.assistant android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.ajax.assistant GET_USAGE_STATS allow
adb shell pm grant com.ajax.assistant android.permission.DUMP
adb shell pm grant com.ajax.assistant android.permission.PACKAGE_USAGE_STATS
```

- `WRITE_SECURE_SETTINGS`: lets Ajax switch its own accessibility service on only while a command runs (see Access.kt). This is what makes GCash work.
- Usage access: lets Ajax see which app is in front (GCash guard, recorder detection).
- `DUMP` + `PACKAGE_USAGE_STATS`: lets Ajax read `dumpsys audio` and `dumpsys activity lru` to name the app using the mic and list background apps.

### Other changes made on their phone (on their request)

- ChatGPT (`com.openai.chatgpt`) and microG (`app.revanced.android.gms`) were force-stopped and restricted: `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`, `RUN_IN_BACKGROUND ignore`, `am set-standby-bucket <pkg> restricted`. microG was also removed from the battery allow-list (`dumpsys deviceidle whitelist -app.revanced.android.gms`). They removed ChatGPT's quick-settings tile themselves (Android 16 refuses `pm disable` of another app's component from the shell). Side effect: ReVanced apps get no notifications while closed. Undo: Settings > Apps > the app > Battery > Unrestricted.
- Screen brightness was set to 128 once after a test turned it to 0.

---

## 3. Repository and builds

- GitHub `sys-kraken/ajax`, working branch `claude/bold-hopper-22ys9x` (also the default branch). Local checkout `C:\Franklin\Projects\ajax`.
- Kotlin, no AndroidX, all views built in code. `minSdk 26`, `targetSdk 35`, `versionName 0.7.0`, `versionCode = 100 + GITHUB_RUN_NUMBER`.
- Native: llama.cpp (pinned, fetched by CMake) built into `libajax_llm` via `app/src/main/cpp` (llm_core.cpp, llm_jni.cpp, eval_main.cpp). arm64-v8a only.
- Dependencies: `com.alphacephei:vosk-android:0.3.75`, `net.java.dev.jna:jna:5.18.1` (aar), `org.tensorflow:tensorflow-lite:2.17.0`, junit for tests.
- Files are CRLF. Scripted edits must read bytes, normalise to LF, edit, and write back with the original line endings. When writing Kotlin from Python, use raw strings: `'\b'` once became a literal backspace in a regex and broke a test.

### Signing

The same key signs debug and release, so builds install over each other and keep data (models, voice print, permissions). The key lives in `C:\Users\sys-admin\.ajax-signing\` (`ajax.keystore`, `keystore-password.txt`). Never commit it or print the password. CI gets it from repo secrets `AJAX_KEYSTORE_B64` and `AJAX_KEYSTORE_PASSWORD`.

### Building on this PC

JDK, SDK and NDK are in `C:\Users\sys-admin\ajax-android`. Gradle needs a short temp path:

```bash
B=/c/Users/sys-admin/ajax-android
export TEMP=C:/Users/sys-admin/ajax-android/tmp TMP=C:/Users/sys-admin/ajax-android/tmp
export JAVA_HOME=$B/jdk-17.0.21+9 ANDROID_HOME=$B/sdk
export AJAX_KEYSTORE=C:/Users/sys-admin/.ajax-signing/ajax.keystore
export AJAX_KEYSTORE_PASSWORD="$(cat /c/Users/sys-admin/.ajax-signing/keystore-password.txt)"
export GITHUB_RUN_NUMBER=70   # bump each local build so it installs over the last one
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/sys-admin/ajax-android/tmp -Djava.io.tmpdir=C:/Users/sys-admin/ajax-android/tmp"
./gradlew --no-daemon testDebugUnitTest assembleDebug
adb install -r "C:\\Franklin\\Projects\\ajax\\app\\build\\outputs\\apk\\debug\\app-debug.apk"
```

The last local build used `GITHUB_RUN_NUMBER=77`. Check what is installed with `adb shell dumpsys package com.ajax.assistant | grep versionCode`.

### CI (`.github/workflows`)

- `build.yml`: unit tests, `assembleDebug` and `assembleRelease`; uploads `ajax-debug-apk` and `ajax-release-apk`.
- `model-eval.yml`: scores the real routing pipeline (regex rules via `EvalExportTest`, then the on-device model on what rules leave) on a main and a holdout set; must reach 85%. Last measured 93.8% main / 96.7% holdout.
- `model-hash.yml`: checks `ModelManager.SHA256` against the real download.
- To wait on CI after a push: `gh run watch <id> --exit-status` (run it in the background).

### Release vs debug

The release build type is not debuggable, so `run-as` stops working and every adb-based test hook in section 7 becomes unusable. Franklin wants the debug build until the app is final.

---

## 4. How Ajax works (architecture)

All source is in `app/src/main/java/com/ajax/assistant/`.

### Entry points

- **Side key / digital assistant**: `AjaxInteractionService` (VoiceInteractionService) shows `AjaxSession` (the overlay). Its `onReady` also restarts wake listening after boot or an update.
- **`ListenActivity`**: invisible launcher entry for shortcuts that can only open apps (RegiStar back tap).
- **`MainActivity`**: the settings screen, opened from Settings > Apps > Ajax (gear), not from an app icon. One UI look, pure black. A slanted "AJAX" wordmark drawn with asterisks (56 columns by 24 rows, made from the image they supplied), in the app icon's green (#A5C736) at the top (their request; hold-to-talk was removed on 2026-11-10, talking happens only in the overlay). Sections with headers: Setup, Wake words (Listen for Ajax, Last heard, the two "ignore during audio" switches), Your voice (the three Teach rows, Voice lock), Calls, Speech, Understanding (model download), System (Run in the background, Accessibility), Advanced (Detector agreement: Strict 0.8 / Balanced 0.5 / Relaxed 0.3; Trained detectors; Wake log with clear), About (version, build). Your voice, Calls and Advanced show only while listening is on.
- **`WakeService`**: always-on listening (section 5).
- **`HandsFree`**: "Ajax" heard with the screen off or locked: a beep, one command through Google speech, a spoken reply.

### Understanding a command

1. `SpeechInput` wraps Android `SpeechRecognizer` (prefers on-device; Google's recogniser service). Reports partials, results and voice level (`onLevel`, used by the orb).
2. `Brain` runs one conversation: yes/no confirmations (`Outcome.Ask`), goodbye words, then routing.
3. `CommandRouter.route(raw)`: regex rules, instant. Before routing, `Memory.resolve` fills in "it/that/him/her/them" from the last app, switch or contact (10 minutes). Order matters; `about()` (who are you, help, hello) is near the front, `openApp` last.
4. If no rule is sure, `IntentModel` asks the on-device LLM (Qwen2.5-0.5B-Instruct Q4_0 GGUF, 428 MB, downloaded once by `ModelManager`, SHA-256 checked). The model only picks one canonical command phrase from a catalog, constrained by a per-request GBNF grammar built from clue words, so it can never invent an action; the phrase then goes back through the router. The evaluated prompt prefix is saved to a file and reused across model loads, the model stays loaded 5 minutes after use, and it is warmed when the overlay opens. Result: about 0.5 s instead of 15 s.
5. A `Match` returns an `Outcome`: `Done(Result)`, `Ask` (yes/no first), or `AfterHide` (needs the accessibility service and the overlay out of the way).

### Doing things

- `Actions`: most commands: torch, volume, ringer, DND, brightness, rotation, media keys, alarms, timers, calls, texts (always asks before sending), drafts, app open / app info, web search, navigation, battery, storage, notifications, running apps, mic users.
- `DeviceInfo`: system report, memory, temperature, uptime, phone model, network.
- `SystemDump`: runs `dumpsys` (needs DUMP) and parses recording sessions and the process list.
- `AjaxAccessibilityService`: global actions (back, home, recents, notifications, lock, screenshot), scroll, tapping Settings switches (Wi-Fi, Bluetooth, mobile data, airplane, location, NFC, battery saver), the power menu (restart / power off, waits for One UI's second press), force stop (App info page > Force stop > confirm), uninstall (Android's uninstall dialog > OK, then checks the app is gone).
- `Access`: runs the accessibility service only while a command needs it (needs WRITE_SECURE_SETTINGS), because GCash refuses to open while any accessibility service is on. Commands that need it are refused while GCash is in front. A service left on (restart, update) is switched off after 2.5 s.
- `CallVoice`: Announce callers: says who is calling, turns the ringtone down to a third, listens for "answer" / "decline" through the wake listener's call grammar; uses `TelecomManager.acceptRingingCall` / `endCall`.

### The overlay (`AjaxSession`, `OrbView`)

Header: an animated orb (Ajax green, teal and blue, drawn in software with additive blending) next to "Ajax" and a status line (Listening, Thinking, Speaking, "Type a command, or tap the mic"). The conversation sits below: blue bubbles for the user, grey for Ajax. The text box has a mic button (talk again after typing) that becomes Send once there is text. Ajax asks music apps to pause (transient audio focus) while it listens. It keeps listening for 12 s after a successful command. Commands that need the accessibility service hide the overlay, run, then reopen it.

### Settings and storage

- `Prefs`: shared preferences keys (speak replies, online speech, wake, calls, voice lock).
- Private files: `models/` (LLM), `wake/` (Vosk keyword model), `voiceprint/` (Vosk speaker model), `voice/` (their 25 "Ajax" recordings), `voiceprint.json`, `wake-scores.tsv` (watch-only log), `cache/prompt-*.kv`.

### Locked-phone and security rules

- Hands-free while locked runs only `WakeWords.allowedHandsFree`: torch, volume, media, ringer, DND, rotation, brightness, battery, time, date, storage, memory, temperature, uptime, phone model, call answer/end, about-Ajax. Everything else says "Unlock your phone for that."
- Texts, calls guessed by the model, restart/power off, force stop and uninstall always ask yes/no first.
- `MicGuard` warns with a notification naming any other app that records from the microphone (from `dumpsys audio` by session id, including from recent history so short recordings are named). Calls are excluded. The warning stays until dismissed.
- The "Ajax on service" notification is reposted if swiped away (Android 14+ allows dismissing foreground notifications).

---

## 5. Always-on listening ("Ajax", lumos, nox, flashlight)

### Pipeline (`WakeService`, `Spotter`, `WakeWords`)

- A microphone foreground service (green dot always on, by design; hiding it was refused). AudioRecord at 16 kHz, `VOICE_RECOGNITION` source, 50 ms chunks.
- `Spotter`: a level gate (MIN 18, 1.7x the tracked noise floor, noise floor rises slowly in a loud room) so silence never reaches the recogniser; 300 ms pre-roll; a phrase ends after 350 ms of quiet or 4 s. Automatic gain lifts quiet speech to normal level before recognition (this is what made quiet and far speech work).
- Recogniser: Vosk small English model (`vosk-model-small-en-us-0.15`, 41 MB, downloaded once, SHA-256 checked) in grammar mode with only the keyword phrases plus `[unk]`. Vosk does not know "lumos"; it hears it as "lou most" / "lou moss", which are in the grammar. One spelling per sound, because sound-alikes split the confidence.
- A keyword must be the whole phrase (so "where is the flashlight" does nothing), and two detectors must agree (`WakeWords.decide`, 2026-11-10, after false triggers during music): Vosk writes the word with enough confidence ("Ajax" 0.7, or 0.75 while audio plays; flashlight words 0.6, or 0.8 while audio plays), and the word's trained detector scores 0.5 or more. When the detector scores 0.9 or more, Vosk only needs 0.5. "nox" and "flashlight off" have no detector; "nox" is ignored when the Ajax detector is sure it was "Ajax".
- While the phone plays audio, "Ajax" also counts with unknown sound around it (`[unk] ajax`), but only when the detector scores 0.9 or more. Other keywords around it ("moss ajax") never count.
- The detector answers a little after the word ends, so `WakeListener` holds a keyword up to 600 ms for its detector before judging it.
- Removed from always-on listening: "loom is", "lumo", "loomis", "lumus", "lumas", "torch on", "torch off" (built from small words Vosk also writes for noise). "torch on/off" still work as commands after "Ajax".
- Settings switches (off by default): "Ignore flashlight words during audio" and "Ignore Ajax during audio" (`Prefs.QUIET_TORCH`, `Prefs.QUIET_WAKE`). They also save the work: an ignored word's detector is not scored while audio plays, and with both on nothing analyses sound at all then (`WakeWords.wanted`, `nothingToHear`; checked once a second in the listener loop).
- Lumos / flashlight turn the torch on and nox / "flashlight off" turn it off, silently, even with the screen off.
- "Ajax" with the screen on and unlocked opens the overlay; otherwise `HandsFree`.
- The listener steps aside (closes the mic) while Ajax listens or speaks (+0.8 s), during calls, voice/video calls and ringing (unless answering by voice), while a recorder or camera app is in front, and whenever another app records. Wake lock only while actually listening.
- CPU: 1 to 4% idle; higher while audio plays on the phone (the gate stays open), 3 to 19% measured before the detectors ran during audio. With the three detectors running during sound, the recorded-speech hook measured 10.9% of one core for the detectors and 13.5% for the whole listener thread on 29 minutes of music and video. Battery drain itself is not measured.
- After a phone restart, Android may block the microphone service from starting in the background; Ajax then posts "Tap to listen for Ajax again". Not yet confirmed on their phone.
- Echo cancellation was tried for the phone's own audio: on Samsung the call echo canceller only works in a call and made playback louder in the mic, so it was removed. Real cancellation needs system access.

### Voice lock (`VoiceLock`)

- Off by default; a switch in settings. When on, "Ajax", lumos, nox and flashlight act only in their voice, so videos, songs and other people cannot trigger Ajax.
- Vosk speaker model `vosk-model-spk-0.4` (14 MB, downloaded with the switch). The voice print is the average fingerprint of their "Teach Ajax your voice" recordings, made on the phone through the same listener and remade when the recordings change (takes about 20 s, listening pauses meanwhile). Threshold: cosine 0.5.
- Measured on the phone: 0 of 128 other-voice clips accepted (synthetic voices, close and with room echo, highest 0.47). Their voice was accepted close up (4/4, 0.73 to 0.84) and 10x quieter (4/4), not from across a room with heavy echo (0/8). A second print from his recordings with simulated echo was tried and dropped because it let 9/64 echoing other voices in. Adds about 60 ms. They confirmed it works.

### The trained detectors (`AjaxDetector`, `WakeListener`): Ajax, lumos, flashlight

- openWakeWord-style: mel spectrogram and Google speech-embedding TFLite models (in `assets/wake/`, Apache 2.0, see NOTICE.txt), shared, then one small MLP per word (`assets/wake/ajax.json`, `lumos.json`, `flashlight.json`, 128 hidden units; `files/wake/<word>.json` on the phone overrides one). A word without a network rests on Vosk alone.
- They run whenever there is sound, including while audio plays, and no longer only watch: a keyword needs both Vosk and its detector (rules above). Alone they are not good enough: on the strict 11-hour test at score 0.5, Ajax gives 28.6 false triggers per hour, lumos 16.9, flashlight 10.9.
- Every phrase is logged to `files/wake-scores.tsv`: time, keyword text or `[unk]`, confidence, Ajax score, length, Ajax threshold, voice similarity, voice frames, playing or quiet, lumos score, flashlight score, what Ajax did. No audio, no other words.
- Lumos and flashlight were trained on 2026-11-10 from their recordings 1 to 20 of each word ("Teach Ajax lumos" / "Teach Ajax flashlight" in settings, stored in `files/voice-lumos`, `files/voice-flashlight`) plus synthetic voices; their own recordings of the other keywords are negatives. Held out: 4 of 5 each (recording 25 of every word is too quiet to use).
- Measured on the phone with the recorded-speech hook, old build 169 against build 172, audio playing: 11 one-minute songs gave 1 false "Ajax" and 3 false flashlight-ons before, none after; speaker-filtered songs and video speech none before or after. Their "Ajax": close 23/25 both; 20 dB quieter 16/16 both; 30 dB quieter 8/16 then 12/16; over music at +10 dB 8/8 both, +5 dB 7/8 then 6/8, equal level 2/8 both. Over someone talking 7/24 then 0/24 (the detector scores near 0 on speech over speech). Lumos and flashlight (4 usable held-out recordings each): 4/4 close, 20 and 30 dB quieter and with room echo; 3/4 over music.
- The three `.json` networks are partly derived from their voice and live in the repo; they were told.

### Training workspace (outside the repo)

`C:\Users\sys-admin\ajax-wakeword`, Python 3.13 venv in `venv/` (piper-tts, openwakeword, onnxruntime, numpy, scipy, scikit-learn).

- `gen.py <pos|near|speech> <count> <seed>`: Piper LibriTTS-R voices (904 speakers, `voices/`) with noise, echo, phone-mic filter and level changes, embedded to `features/*.npy`.
- `gen_real.py "<glob>" <variations> <name>`: variations of real recordings (their `voice_train/`, Windows SAPI `sapi/`).
- `train.py`: numpy MLP with hard-example mining (`HIDDEN`, `NEG_WEIGHT`, `STEPS` env vars); negatives include 1.5 GB of openWakeWord's ACAV100M features (`data/acav_part.npy`) and `data/validation_set_features.npy` (11 h) for the false-trigger count; writes `model/ajax.json`.
- `WORD=lumos` or `WORD=flashlight` (environment) makes `gen.py`, `gen_real.py` and `train.py` work on that word (`features_<word>/`, `model/<word>.json`); `EXTRA_NEG` adds feature files as negatives. `run_words_synth.sh` makes the synthetic clips; `run_words_real.sh` pulls the phone recordings into `rec/`, trains both words and scores the held-out ones.
- `score_voice.py`, `phone_test.sh <folder> <out>`, `summarize.py`, `vl_summary.py`: test helpers (section 7). `run_before.sh <outdir>` runs every set through the build on the phone; `compare.py <dir> old|new` prints the per-set table (`before/` = build 169, `after3/` = build 172). `neg_music*`, `neg_video*` are one-minute clips cut from local music and video audio; `words_test/` is made by `make_word_tests.py`.
- Their recordings: `voice/` (all 25), `voice_train/` (1 to 20), `voice_test/` (21 to 25). Recordings 1 and 25 are too quiet to use.
- Colab is signed in to their Google account in the built-in browser with a T4 runtime selected, if a much bigger training run is ever needed. openWakeWord's official notebook pins old libraries that likely won't install; run these scripts there instead.

---

## 6. Feature status

Working and confirmed by Franklin on the phone:

- Side key overlay, follow-up listening, typed commands, the orb and redesigned overlay
- Restart through the power menu
- GCash opening with Ajax installed (accessibility only during commands)
- Always-on "Ajax", lumos/nox/flashlight, quiet speech
- Mic alert naming the app (tested with Voice Recorder)
- "Ajax on service" notification that comes back
- Voice lock
- Uninstalling an app by voice (removed Clash of Clans)
- System report, battery phrasing, running apps, mic users, force stop (tested on Calculator)
- "Who are you" and the other about-Ajax answers

Built and unit-tested, but not confirmed on the phone:

- Calls by voice (announce, answer/decline): never tried with a real incoming call. Android may refuse `acceptRingingCall`/`endCall` for a non-dialer app; Ajax then says to swipe.
- Listening resuming by itself after a phone restart
- Whispering (only quiet talking was tested), across-the-room and over-music with their live voice
- The two-detector rules and the lumos / flashlight detectors with their live voice (measured with recordings only), and the two "during audio" switches (unit-tested only)
- The regrouped settings screen and the ASCII wordmark (build 177: wordmark and the upper sections checked in screenshots from the phone; Advanced, About and the dialogs not looked at)
- Battery use over a full day
- Stopping a whole list of background apps at once ("terminate them")
- Tapping Settings switches (Wi-Fi etc.) under on-demand accessibility

---

## 7. Testing

- Unit tests: 94, all passing (`testDebugUnitTest`). Routing is tested heavily (`CommandRouterTest`, `DeviceInfoRoutingTest`, `AboutAjaxTest`, `CallVoiceTest`, `MemoryTest`, `WakeWordsTest`, `SystemDumpTest`, `ServiceListTest`, `IntentModelTest`, `RecognizersTest`, `EvalExportTest`).
- **Recorded-speech hook (debug builds only):** 16 kHz mono WAVs moved into the app's `files/wake-test/` are fed through the real Spotter, the detector and the voice lock on the phone; results go to `files/wake-test/results.txt` (text, confidence, voice similarity and frames, timing, detector score, decision). `phone_test.sh` stages them (copy, then move in one go) and waits for the results. Test checks are wrapped so they can never stop listening (a bad parse once crashed the listener). Each phrase is judged twice, as in a quiet room and as with audio playing (`quiet=` / `playing=`), and each file ends with the detectors' and the listener's CPU time.
- **Echo probe:** `files/aec-probe` (+ optional `files/aec-play.wav`) records 3 s through each microphone setting and writes levels to `files/aec-result.txt`.
- **Driving the overlay:** `adb shell am start -n com.ajax.assistant/.ListenActivity`, find the EditText bounds with `uiautomator dump`, tap it, `input text "battery%spercent"`, `input keyevent KEYCODE_ENTER`, read Ajax's text nodes from another dump. Commands typed this way really run (a dimming test once set their brightness to 0).
- Screenshots: `adb exec-out screencap -p`. Crop to Ajax's area; their home screen and notifications are private.

---

## 8. Known limits and decisions already made

- The green mic dot cannot be hidden without root; hiding it was refused. Root was discussed and rejected because GCash (and Knox) would break.
- No app can be trusted by GCash by setting; the fix was running accessibility only during commands.
- Android 16 refuses shell `pm disable` on another app's component.
- Echo cancellation of the phone's own audio is not possible for a normal app here.
- With the voice lock on they must be in the same room and fairly close.
- A video or podcast talking on the phone defeats "Ajax" (speech over speech); music is mostly fine when they are louder than it.

---

## 9. Suggested next steps (they have seen this list)

1. A searchable list of commands in Ajax's settings (quick, everyday value).
2. Reminders and notes ("remind me at 5 to call mom", "note: buy milk").
3. Read and reply to messages through notification reply actions (Messenger, WhatsApp, Telegram, SMS), always confirming before sending.
4. Answers to general questions: online Claude, or a bigger offline model (`model-eval.yml` already scores Qwen2.5-1.5B as a candidate). They have not chosen.
5. Routines ("good night": DND, low brightness, alarm, Wi-Fi off).
6. Real across-the-room recordings for the voice lock.
7. Real-world checks: a real incoming call, a restart, a full day of battery.
8. When they call it final: install the release build and stop using the debug-only test hooks.
