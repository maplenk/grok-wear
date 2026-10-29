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
- **Live updates:** while the app is open, and for up to ten minutes after you send a message, the watch keeps a connection open to your Grok Bot gateway with the same sign-in, to hear about Bot list changes and new chat messages as they happen, as the official apps do. Nothing extra is sent over it.
- **To the Bot, after a call:** for Bots that support it, the same end-of-call note the official apps send ("The call ended…"), then up to the last six things you said on the call, so the Bot can follow up in chat. It is sent once and never retried.
- **To xAI's realtime voice service:** your microphone audio while a call is active, short-lived call credentials, and the call's instructions for the Bot. Like the official apps, those instructions include the date and time the call started and the watch's time zone (for example "Asia/Shanghai"), so the Bot can answer "what day is it". The Bot's spoken replies come back the same way.

When a Bot replies after you have left the app, the watch shows a notification with the Bot's name and the start of its reply. On the lock screen it shows only "New reply". Opening that chat removes it.

Microphone audio is captured only during a call you start (by tapping Talk, with the watch's assistant button or by saying “Hello Grok”), and only while it is not muted. Audio is streamed, never written to storage, and not sent anywhere else.

**“Hello Grok”** is off unless you turn it on. While it is on, the watch listens for the phrase on the watch itself: audio is checked in memory a fraction of a second at a time, then discarded, never stored and never sent. When the first model thinks it heard the phrase, a second on-device model checks the last two seconds again, also in memory only. When it hears the phrase, it keeps up to six seconds of audio from that moment (what you say next) in memory and sends it as the start of the call, so a question in the same breath reaches the Bot; if no call starts within 15 seconds, that audio is discarded. It listens only while the screen is on, and pauses when the watch is off your wrist, in Theater mode or Battery Saver, below 15% battery and during calls, and while it runs the watch shows its microphone indicator and an ongoing “Listening for Hello Grok” notification.

## Permissions

| Permission | Why |
|---|---|
| Microphone | To hear you during a call you start, and to listen for “Hello Grok” if you turn it on. |
| Internet / network state | To reach Grok Bot and detect a lost connection. |
| Foreground service (microphone, media playback) | To keep a call running when you leave the app, and to listen for “Hello Grok” in the background. |
| Foreground service (data sync) | To keep listening for a Bot's reply for a few minutes after you send a message and leave the app. |
| Notifications | To show the ongoing call so you can return to it or hang up, and a Bot's reply to a message you sent. |
| Wake lock | To keep a call alive while the screen is off. |
| Modify audio settings / Bluetooth connect | To route call audio. |
| Vibrate | A short buzz when “Hello Grok” is heard. |
| Digital assistant (chosen by you in watch settings) | Lets Grok Wear listen for “Hello Grok” while the app is closed, open the call screen when it hears it, and answer the watch's assistant button. Android shows that an assistant can read what's on screen; Grok Wear doesn't use that. |

## Your choices

Turn reply alerts, the assistant button and “Hello Grok” off in the app's Settings, or decline the first two when you sign in. To give the assistant role back, choose another digital assistant in the watch's settings (Settings › Apps › Default apps › Digital assistant app). Mute the microphone or hang up at any time. Revoke the microphone permission in watch settings, or sign out in the app's Settings to remove all local data.
