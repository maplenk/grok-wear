# Architecture

Grok Wear is a standalone Wear OS app written in Java with a programmatic UI. It talks to the Grok Bot service directly from the watch; there is no phone companion or relay server.

```text
Sign in: watch QR code ── phone browser approval ── encrypted tokens on the watch

Talk:    sign-in check ─┬─ Bot voice setup (instructions + tools) ─┐
                        ├─ short-lived voice credential ───────────┼─ realtime socket ─ settings
                        └─ warm connection to the voice host ──────┘        │   (audio set up
                                                                            │    while dialling)
                                             microphone opens ── 24 kHz PCM both ways ── Hang up
                                                     │ line drops: redial the same conversation
                                                                            │
                                                  final transcript saved to the Bot's history
                                                                            │
                                                  end-of-call handoff to the Bot (send_task)
```

## Components

| Class | Role |
|---|---|
| `GrokAuthCore`, `GrokAuthClient`, `GrokAuthVault` | Browser/QR sign-in with a watch-held verifier, token refresh, and connection bootstrap. A 401 or 403 from the sign-in poll is Grok refusing the sign-in (Grok Bot Android 1.25.0), so polling stops and Needs attention says so. Callers that need either while one is running wait for it instead of failing. A refresh is never retried after an uncertain outcome. |
| `GrokVault`, `VaultSnapshotCache` | AES-GCM encrypted files with a non-exportable Android Keystore key, plus an in-memory snapshot cache that is invalidated on every write. |
| `GrokCore`, `BotAccount` | Bot roster, Primary/pinned preferences, pin and unpin, and the narrow set of allowed service operations. Every request rechecks that the account and selected Bot have not changed. A pin write re-reads the account's list first, changes only that Bot (pins are added at the end), sends the whole list back, and keeps the list the server stored. |
| `RosterStream`, `PhotoShrink` | Read the Bot list as it arrives. The official apps save a custom Bot photo as a 256 px PNG (often 100–200 KB, up to 1 MB), so a list with a few custom photos passes the 1 MB cap other replies have. Each photo is shrunk as soon as it arrives to a 160 px WebP of a few KB (the largest avatar is 64 dp), and shrunk photos are kept within a 1 MB budget, about 100 Bots. Other long strings the Bot list doesn't use are emptied. The read stops at 32 MB downloaded or 8 MB kept. |
| `HistoryCoordinator` | Shared-chat pages for the Bot page, and a durable outbox for call transcripts. |
| `VoiceStartup` | Runs Bot voice setup and credential minting in parallel under a 45-second budget. |
| `NativeVoiceSession` | One realtime voice session: settings (including the Bot's own voice, speed and language), turn handling, interruption, the tool allowlist, the caller-first greeting and reconnecting a dropped line. |
| `NativeAudio`, `PcmPlaybackBuffer` | Microphone capture and speaker playback (24 kHz mono PCM16) with echo cancellation where available, a bounded playback buffer, audio-focus pause and resume, and routing to a headset or the speaker. On the watch speaker the voice plays as media, since watches play call audio quietly; call mode stays on for the microphone's processing and echo cancelling. A headset, or media volume at zero, keeps call audio. |
| `VoiceService` | Foreground service that owns a call: admission, ongoing notification, network loss detection, captions and saving the transcript. Like the official apps, for every harness it tells the Bot's gateway the call is on the line (`setVoiceCallPresence {id, callId, isOnTheLine, acceptsOverheard}`, with the call's `call-<uuid>` id) and off it at the end, and listens to the live event stream during the call for that call's finished work (`CallNudges`). |
| `CallHandoff` | The end-of-call note and leftover request sent to the Bot after the transcript is saved. |
| `BotEvents`, `EventStream` | The gateway's live event stream (`/events`, read as the official Android app reads it): roster changes, new chat entries and a voice call's finished work. One connection while the app is open, a voice call runs or a reply alert waits. Events only refresh what's shown; the roster and chat are still read the usual way, and the gateway's trays (`getTrays`) once per connect. A live row saying a Bot's chat was read elsewhere (nothing unread, a `lastViewedAt`) removes that Bot's reply notice posted before the read. |
| `ServerTranscripts`, `ServerEvents` | Bots that run on Grok's servers (`harness: temporal`, Grok Bot Android 1.25.0). Their chat pages, sends, read marks and call records go to Grok's backend (`ListGrokBotTranscriptEntries`, `SendGrokBotUserMessage`, `SetGrokBotAgentClientState`, `RecordGrokBotVoiceCall`) instead of the gateway, and Grok's own stream (`WatchGrokBotTranscripts`, Connect server-streaming) supplies their unread counts, previews, working and "Needs you" (`awaiting`) state and the open chat's new entries, handed on as gateway events. The roster still merges the gateway's rows (photos, titles). |
| `SendStatus` | Settles a send through `SendGrokBotUserMessage` whose answer was lost, as Grok Bot Android 1.25.0 does, with `GetGrokBotSendStatus` (see Reliability). Never by sending it again. |
| `CallQuestions` | A question someone on the Bot's phone call asks (a user form with a `phoneCallQuestion`, MORE-FEATURES-SPEC §1): held back until `revealAtMs`, a countdown to `deadlineAtMs` that settles to "No reply in time" on the watch, and Send or Skip, once, through the gateway's `submitUserForm` / `dismissUserForm` (box Bots) or `SubmitGrokBotUserForm` / `DismissGrokBotUserForm` (Bots on Grok's servers). A waiting reply alert buzzes with it. |
| `Widgets` | A Bot's question with option buttons (a `widget` message, MORE-FEATURES-SPEC §5): open, answered (the answer on the same entry, `respondedValue`), echoed as your own message, dismissed, or stale once you write after a `dismissOnMoveOn` question. One tap sends an option's value (else its label), several picks go one per line, ✕ dismisses; once, through the gateway's `respondToWidget` / `dismissWidget` (box Bots) or `RespondGrokBotWidget` / `DismissGrokBotWidget` (Bots on Grok's servers), shown at once and reverted if Grok refuses. The phone's own options (choose the main Bot, start a call) and special forms stay on the phone, and so do pending asks to run a tool on the phone (`local-tool-permission`, `run-phone-tool`). |
| `CallNudges` | Finished work reaching your own voice call (`voice-call-nudge` on the gateway's stream, MORE-FEATURES-SPEC §2), matched to the call by Bot and call id while you're on the line, once per update: ack, progress and outcome become one spoken `async_update.add` (topic `work_landed`), and the next harness tool request owes the news until the Bot speaks; overheard notes become a silent `work_overheard` function-call pair, only when the call's plan has `hasOverheard`. |
| `UsageTrays`, `Approvals`, `PhoneCalls` | Grok's usage-limit notices (gateway trays, pushed or read with `getTrays` when the stream connects, and the server's `turnFailed`), a Bot's requests for your OK (Allow and Deny, answered through the gateway or Grok's backend, never "always"), and the phone calls a Bot places (End call, Take over), each read from chat entries as the official Android app reads them. |
| `AssistantRole`, `GrokAssistantService`, `GrokAssistantSessionService`, `GrokRecognitionService` | The assistant button. Once Grok Wear is chosen as the watch's digital assistant, holding the button opens a session that starts a call with the main Bot (the account's Primary Bot), switching to it first if another Bot is open. Nothing listens in the background; Android requires an assistant to name a speech recognizer, and this one refuses every request. |
| `ProblemReport` | A private 15-line log of Bot list loads (first loads, recoveries and slow ones), calls and failures, with timing, network type and reason (the app's own message, a status code or an error type, never a network message, which can name the account's server). Report a problem (in Settings, and on the Bot list, call and sign-in error screens, naming that place) turns it into a QR code for an email to the developer (`mailto:`, the default) or a new GitHub issue, keeping the newest lines that fit a 900-character link, drawn one pixel per module so it scans from a watch screen. The latest failure, and any crash (written before the app closes), is kept as a trace: the root error type, the app's own limit when one was hit, and up to four frames of the app's own code, never a platform or network message. The report carries it as an "Error:" line, and the Bot list's Details screen shows it with the same log. |
| `ReplyAlertService` | Foreground service that waits for the reply to a message sent from the watch, then posts a notification that opens the chat. It stays quiet if that chat is on screen, and the notice goes once the chat is read, here or elsewhere. |
| `MainActivity` and views | Round-screen UI: Home roster, Bot page with chat, call screen, sign-in and settings. |

## Call rules

- A call's setup reads only the sign-in tokens, so it never waits for, or fails with, a gateway bootstrap (`EnsureSandBox`, every 5 minutes, which waits while the Bot's sandbox starts and gets up to 60 s). Bootstraps themselves are still shared and never replayed automatically.
- A call starts only from an explicit Talk tap or the watch's assistant button, on an unlocked, visible watch. Opening or resuming the app otherwise never starts one.
- Like the official apps, a call doesn't preload chat history. The Bot's instructions and its lookup tools give it context. Audio follows the settings on the same socket as soon as it opens; an error or close before Grok confirms the settings fails the start, and settings never confirmed end the call after 10 seconds.
- The voice model gets the official clients' tools when the Bot's harness offers them: `recall_text_messages`, `search_conversations`, `stay_silent`, `end_the_call` and `send_task` (a task handed to the Bot's main agent). All are served by `VoiceCallHarnessTool`; anything else is refused locally. Caller lines already handed off as a task are left out of the end-of-call handoff.
- The caller goes first. If nobody speaks within 15 seconds, the Bot gets its greeting.
- Each call is capped at five minutes.
- After the transcript is saved, Bots on the `temporal` harness get the official end-of-call handoff through `VoiceCallHarnessTool` (`send_task`). First comes the fixed "The call ended…" notice, then the caller's last request with up to five earlier lines quoted (2,000 characters at most). Sending stops at the first reply that is not accepted, and nothing is retried, so a request never reaches the Bot twice. It goes to the Bot that took the call, even if another Bot is selected by then.

## Reliability on watch networks

- Idempotent history reads give up after 8 seconds without data and retry once. Writes are never retried blindly.
- A voice-call transcript that cannot load is shown as a summary instead of blocking the call.
- A WebSocket ping every 15 seconds catches silently dead connections.
- A live call whose line drops redials, like the official Android app: a fresh credential (over a fresh connection, 10-second deadline), the same conversation, the first try at once and then 1 s ×1.2 up to 10 s, at most 20 tries, 15 seconds per dial. A network coming back redials at once. If the server starts a new conversation, the call's turns are replayed. Policy and superseded closes, the caller's hang-up, and calls that never went live are not redialled.
- A short audio interruption (alarm, timer, assistant) pauses the microphone and speaker until focus returns; only a permanent loss of audio focus ends the call. Failed microphone reads are retried for about 2 seconds.
- If speech never turns into a reply, the call returns to Listening after 8 seconds.
- Grok's server stream reconnects like the official client: 0.5 to 3 seconds after a clean end, with backoff from 1 second to a minute after errors. Following a newly opened chat redials at once, after a 250 ms pause that gathers several. When Grok can't replay from where the watch left off, the chat is read again.
- The live event stream treats 45 seconds without data as a dead link (the gateway sends a keep-alive every 15 seconds), dials a fresh connection each time, and reconnects at once on a new network. After a gap, the app and a waiting reply alert read the chat again instead of trusting events they may have missed.
- A message whose send went unanswered is never sent again on its own. For a send through Grok's server, the watch asks `GetGrokBotSendStatus` every 2 seconds (5-second deadline each) until Grok says accepted (Sent), rejected or not found (Not sent), giving up after a minute or three asks in a row that don't get through; then, as for a gateway send, the chat is read for it. A message Grok never got is sent again only when you tap it, with the same ID.
- A reply alert ends at the Bot's first message after yours (found by order in the chat, not by clocks), ten minutes after sending, or once the Bot has been idle for two minutes. Android blocks network access for apps in the background, so the alert runs as a foreground service.

## Limits

| Limit | Value |
|---|---|
| HTTP calls | 30 s total, 1 MiB response, no redirects |
| Realtime events | 512 KiB each; 256 KiB outgoing queue |
| Playback buffer | 2 MiB of PCM (about 44 s) |
| Local history store | 1.5 MiB; 128 KiB per call record |

## Storage and sign-out

Account data and chat cache use separate Keystore keys and no-backup files; cloud backup and device transfer are excluded. Account screens set `FLAG_SECURE`. Signing out deletes local credentials, cache and pending transcripts. It does not delete anything from the Grok account.
