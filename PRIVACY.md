# Privacy

Grok Wear has no servers of its own and collects nothing for its maintainers: no analytics, crash reporting, ads or tracking. Everything below describes data that moves between your watch and the Grok Bot service you already use.

## What stays on the watch

- **Sign-in.** Your Grok Bot sign-in (access and refresh tokens) is stored in the app's private storage, encrypted with a key held in the Android Keystore. It is excluded from cloud backup and device transfer.
- **Bot list and recent chat.** Your Bot roster and a bounded copy of recent chat are cached, encrypted the same way, so screens open quickly.
- **Unsent call transcripts.** If a call's transcript cannot be uploaded right away, it waits encrypted on the watch until it can.

Signing out removes all of this from the watch. It does not delete anything from your Grok account.

## What is sent, and where

- **To Grok Bot's servers:** sign-in, your Bot list, recent chat for the Bot you open, and the final text of each voice call, which is saved to that Bot's history as the official apps do.
- **When you pin or unpin a Bot:** your account's pinned-Bot list, as the official apps save it.
- **When you send a text message:** the text you reviewed, to that Bot's chat, and a "read" mark when you open the chat, as the official apps do. The watch keeps your last few messages and their delivery state until you sign out.
- **To the Bot, after a call:** for Bots that support it, the same end-of-call note the official apps send ("The call ended…"), then up to the last six things you said on the call, so the Bot can follow up in chat. It is sent once and never retried.
- **To xAI's realtime voice service:** your microphone audio while a call is active, and short-lived call credentials. The Bot's spoken replies come back the same way.

Microphone audio is captured only during a call you started by tapping Talk, and only while it is not muted. Audio is streamed, never written to storage, and not sent anywhere else.

## Permissions

| Permission | Why |
|---|---|
| Microphone | To hear you during a call you start. |
| Internet / network state | To reach Grok Bot and detect a lost connection. |
| Foreground service (microphone, media playback) | To keep a call running when you leave the app. |
| Notifications | To show the ongoing call so you can return to it or hang up. |
| Wake lock | To keep a call alive while the screen is off. |
| Modify audio settings / Bluetooth connect | To route call audio. |

## Your choices

Mute the microphone or hang up at any time. Revoke the microphone permission in watch settings, or sign out in the app's Settings to remove all local data.
