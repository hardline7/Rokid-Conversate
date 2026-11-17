# Rokid AI helper

For installation without building, download the Windows Setup ZIP from
[Releases](https://github.com/hardline7/Rokid-Conversate/releases/tag/v0.3.4) and follow
[the complete APK + helper guide](../../docs/INSTALL.en.md). The packaged
`Install-Conversate.ps1` installs both components; this page describes the helper alone.

The Android app delegates live speech-group summaries, term explanations, cues, interface translation and recap generation to an ordinary AIUI agent.
The helper uses Rokid's documented `LanguageModel` capability. Rokid credentials
stay inside the host runtime; the APK never obtains them and has no external AI
API key.

Install the latest Android APK and open Conversate once. While no meeting is
active, connect exactly one authorized glasses device and run from the project root:

```powershell
.\tools\rokid-ai\Install-Helper.ps1
```

The installer automatically selects the single connected device. For multiple
devices, pass `-Serial <adb-device-id>`. This prepares the pinned official AIX CLI locally, checks and installs the helper,
enables supported Rokid Developer Mode, and adds its Widget to the launcher. It
briefly shows the launcher so that the Widget attaches, then returns to the native
meeting screen. It does not start speech recognition. Developer Mode remains
enabled because the local helper needs it. The existing layout is preserved by
the official CLI; the initial test device had no configured Widgets or developer
agents.

The installer reads only Conversate's own random local relay secret from
`files/ai_bridge_token`. It passes it through a temporary launch-parameters file
and removes that file afterward. Do not print or commit that secret. The `.aix`
package does not embed it.

Runtime protocol (HTTP loopback, port 8766):

- `X-Conversate-Bridge`: our app's relay token.
- `WS /bridge/lifecycle`: token-authenticated passive app-open/app-close events; no ping/reconnect timers.
- `POST /bridge/heartbeat`: availability at startup and during active generation.
- `GET /bridge/status`: authenticated transport diagnostics.
- `GET /bridge/job`: one queued task with `id`, `system`, `prompt`, `deadline`.
- `POST /bridge/result`: `{id,ok:true,output}` or `{id,ok:false,error}`.

The helper creates and destroys an independent LanguageModel session per task.
Model output is validated by the Android application before storage/display.
Network JSON uses explicit UTF-8. It does not open a microphone or write audio.

`-Probe` runs a short synthetic test prompt. Production jobs follow the glasses language. `-Page` is a diagnostic assistant
card and can cover the meeting view; normal installation uses Widget mode only.
Do not use the diagnostic Page for routine meetings. All local AIX commands are
available under `cli/node_modules/.bin/aix.cmd`.

Verified on this connected device: real Korean LanguageModel response, authenticated
relay, and Widget heartbeats continuing after the native meeting screen was
restored and diagnostic assistant cards were dismissed. See
[implementation status](../../docs/BUILD-STATUS.md). Raw captures and firmware extracts are not published.

The widget starts no AI worker while the native app is closed. The cached native process keeps only the passive lifecycle channel. Reboot, force-stop and OS process reclamation require rerunning the helper setup after opening the app; automatic recovery is not implemented. See [POWER.md](../../docs/POWER.md).
