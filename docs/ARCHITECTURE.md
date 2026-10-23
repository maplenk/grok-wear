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
| `GrokAuthCore`, `GrokAuthClient`, `GrokAuthVault` | Browser/QR sign-in with a watch-held verifier, token refresh, and connection bootstrap. A refresh is never retried after an uncertain outcome. |
| `GrokVault`, `VaultSnapshotCache` | AES-GCM encrypted files with a non-exportable Android Keystore key, plus an in-memory snapshot cache that is invalidated on every write. |
| `GrokCore`, `BotAccount` | Bot roster, Primary/pinned preferences, pin and unpin, and the narrow set of allowed service operations. Every request rechecks that the account and selected Bot have not changed. A pin write re-reads the account's list first, changes only that Bot (pins are added at the end), sends the whole list back, and keeps the list the server stored. |
| `HistoryCoordinator` | Shared-chat pages for the Bot page, and a durable outbox for call transcripts. |
| `VoiceStartup` | Runs Bot voice setup and credential minting in parallel under a 45-second budget. |
| `NativeVoiceSession` | One realtime voice session: settings (including the Bot's own voice, speed and language), turn handling, interruption, the read-only tool allowlist, the caller-first greeting and reconnecting a dropped line. |
| `NativeAudio`, `PcmPlaybackBuffer` | Microphone capture and speaker playback (24 kHz mono PCM16) with echo cancellation where available, a bounded playback buffer, audio-focus pause and resume, and routing to a headset or the speaker. |
| `VoiceService` | Foreground service that owns a call: admission, ongoing notification, network loss detection, captions and saving the transcript. |
| `CallHandoff` | The end-of-call note and leftover request sent to the Bot after the transcript is saved. |
| `MainActivity` and views | Round-screen UI: Home roster, Bot page with chat, call screen, sign-in and settings. |

## Call rules

- A call starts only from an explicit Talk tap on an unlocked, visible watch. Opening or resuming the app never starts one.
- Like the official apps, a call doesn't preload chat history. The Bot's instructions and its lookup tools give it context. Audio follows the settings on the same socket as soon as it opens; an error or close before Grok confirms the settings fails the start, and settings never confirmed end the call after 10 seconds.
- Only read-only tools are offered to the voice model (`recall_text_messages`, `search_conversations`, `stay_silent`, `end_the_call`). Anything else is refused locally.
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

## Limits

| Limit | Value |
|---|---|
| HTTP calls | 30 s total, 1 MiB response, no redirects |
| Realtime events | 512 KiB each; 256 KiB outgoing queue |
| Playback buffer | 2 MiB of PCM (about 44 s) |
| Local history store | 1.5 MiB; 128 KiB per call record |

## Storage and sign-out

Account data and chat cache use separate Keystore keys and no-backup files; cloud backup and device transfer are excluded. Account screens set `FLAG_SECURE`. Signing out deletes local credentials, cache and pending transcripts. It does not delete anything from the Grok account.
