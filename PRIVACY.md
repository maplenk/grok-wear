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

**Report a problem** sends nothing from the watch. It shows a QR code that opens, on your phone, an email to the developer (namankhator@gmail.com), or if you choose, a new GitHub issue. Either is filled in with your watch model, the Android and app versions, whether the assistant button is on, the network type with Android's speed estimate and the Wi-Fi signal strength, the app's state (how many Bots, whether a main Bot is set, when the list last loaded, whether reply alerts are on, and the battery level and Battery Saver), and as much of a short log as fits. The log holds the last 15 events: Bot list loads (a run of successful ones as one line), calls and failures, when each happened, how long it took, the network type and speed estimate, the size of a Bot list download and where its time went (setup, waiting for the server, downloading), and why it failed, as the app's own message, a status code or an error type. It never holds message text, Bot names or server addresses. Nothing is sent unless you send the email or submit the issue. An email reaches only the developer, along with your email address; GitHub issues are public. The watch keeps the log locally, shows it under the Bot list's Details, and clears it when you sign out or uninstall.

When a Bot replies after you have left the app, the watch shows a notification with the Bot's name and the start of its reply. On the lock screen it shows only "New reply". Opening that chat removes it.

Microphone audio is captured only during a call you started, by tapping Talk or holding the watch's assistant button, and only while it is not muted. Audio is streamed, never written to storage, and not sent anywhere else.

## Permissions

| Permission | Why |
|---|---|
| Microphone | To hear you during a call you start. |
| Internet / network state | To reach Grok Bot and detect a lost connection. |
| Foreground service (microphone, media playback) | To keep a call running when you leave the app. |
| Foreground service (data sync) | To keep listening for a Bot's reply for a few minutes after you send a message and leave the app. |
| Notifications | To show the ongoing call so you can return to it or hang up, and a Bot's reply to a message you sent. |
| Wake lock | To keep a call alive while the screen is off. |
| Modify audio settings / Bluetooth connect | To route call audio. |

## Your choices

Turn reply alerts off in the app's Settings, or decline them when you sign in. The assistant button is off unless you choose Grok Wear as the watch's digital assistant; choose another assistant to turn it off. Mute the microphone or hang up at any time. Revoke the microphone permission in watch settings, or sign out in the app's Settings to remove all local data.
