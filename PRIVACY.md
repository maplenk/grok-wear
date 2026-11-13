# Privacy

Grok Wear has no servers of its own and collects nothing for its maintainers on its own: no analytics, automatic crash reporting, ads or tracking. The one exception is **Send logs** in Report a problem, which is off by default and sends only when you turn it on and tap Send (see below). Everything else below describes data that moves between your watch and the Grok Bot service you already use.

## What stays on the watch

- **Sign-in.** Your Grok Bot sign-in (access and refresh tokens) is stored in the app's private storage, encrypted with a key held in the Android Keystore. It is excluded from cloud backup and device transfer.
- **Bot list and recent chat.** Your Bot roster and a bounded copy of recent chat are cached, encrypted the same way, so screens open quickly.
- **Unsent call transcripts.** If a call's transcript cannot be uploaded right away, it waits encrypted on the watch until it can.

Signing out removes all of this from the watch. It does not delete anything from your Grok account.

## What is sent, and where

- **To Grok Bot's servers:** sign-in, your Bot list, recent chat for the Bot you open, and the final text of each voice call, which is saved to that Bot's history as the official apps do. When Grok refuses your account, the watch asks Grok's access status (whether this account can use Grok Bot, and why not), as the official apps do, and shows the answer with a QR code to Grok's own page for fixing it.
- **When Grok says the app's version is too old:** the watch asks Grok's update server (the same Grok server it signs in with) for the newest Grok Bot version, the way the Grok Bot desktop app checks for updates, and uses that version from then on. This request carries no sign-in or account details, only the version in use and a random ID that is different every time.
- **When you pin or unpin a Bot:** your account's pinned-Bot list, as the official apps save it.
- **When you send a text message:** the text you reviewed, to that Bot's chat, and a "read" mark when you open the chat, as the official apps do. For Bots that run on Grok's servers, the message also carries the watch's language and time zone (for example "en" and "Asia/Shanghai"), as the phone app sends them. If Grok's answer to a message is lost on the way, the watch asks Grok what became of that message, every 2 seconds for up to a minute, as the phone app does; each ask names only the Bot and the message's random ID (and, for a teammate's Bot, your conversation with it), never its text. The watch keeps your last few messages and their delivery state until you sign out.
- **When you answer a Bot's request for your OK:** your answer (allow or deny, never "always") and which request it answers, to Grok, as the phone app sends it.
- **When you end or take over a phone call your Bot is placing:** that request, naming only the call, to Grok, as the phone app sends it.
- **When you answer a Bot's question with ready answers:** the answer you tapped (the option's own value) or that you dismissed the question, and which question it answers, to Grok, as the phone app sends it. Sent once, never again on its own.
- **When you answer a question from your Bot's phone call:** your answer as you reviewed it (or that you skipped it), and which question it answers, to Grok, as the phone app sends it. Sent once, never again on its own.
- **During a voice call:** the watch tells your Bot's gateway when the call starts and ends, as the official apps do, so the Bot's finished work can reach the call. That carries only the Bot, a random ID made for the call (the same one its lookups and saved transcript use), and whether the Bot may pass on notes about work in progress.
- **Live updates:** while the app is open, during a voice call, and for up to ten minutes after you send a message, the watch keeps a connection open to your Grok Bot gateway with the same sign-in, to hear about Bot list changes and new chat messages as they happen, as the official apps do. Nothing extra is sent over it. If you have Bots that run on Grok's servers, a second connection to Grok does the same for them, as the phone app does; it tells Grok only which of your chats to follow and how far the watch has read. The watch also reads Grok's own "usage limit" notices this way. Each time the connection opens, it asks the gateway once for the notices it currently has, as the official apps do, so one given while the watch was offline isn't missed; that request carries nothing beyond your existing sign-in. For the Bot you have open it may ask Grok's usage status once per notice, as the official apps do, only to show when your usage resets; that request carries nothing beyond your existing sign-in.
- **To the Bot, after a call:** for Bots that support it, the same end-of-call note the official apps send ("The call ended…"), then up to the last six things you said on the call, so the Bot can follow up in chat. It is sent once and never retried.
- **To xAI's realtime voice service:** your microphone audio while a call is active, short-lived call credentials, and the call's instructions for the Bot. Like the official apps, those instructions include the date and time the call started and the watch's time zone (for example "Asia/Shanghai"), so the Bot can answer "what day is it". The Bot's spoken replies come back the same way.

**Report a problem** sends nothing from the watch. It shows a QR code that opens, on your phone, an email to the developer (namankhator@gmail.com), or if you choose, a new GitHub issue. Either is filled in with your watch model, the Android and app versions, whether the assistant button is on, the network type with Android's speed estimate and the Wi-Fi signal strength, the app's state (how many Bots, whether a main Bot is set, when the list last loaded, whether reply alerts are on, and the battery level and Battery Saver), the latest error's trace, and as much of a short log as fits. The watch keeps the last 60 events; the QR code carries as many of the newest as fit. They are: Bot list loads (a run of successful ones as one line), calls and failures, when each happened, how long it took, the network type and speed estimate, the size of a Bot list download and where its time went (setup, waiting for the server, downloading), and why it failed, as the app's own message, a status code or an error type. The latest failure, or a crash, is also kept as a short trace: its error type, the app's own limit when one was reached (such as "reply over 1 MB"), and where in Grok Wear's own code it happened (class, method and line). It never holds message text, Bot names, server addresses or messages from the network. Nothing is sent unless you send the email or submit the issue. An email reaches only the developer, along with your email address; GitHub issues are public. The watch keeps the log locally, shows it under the Bot list's Details, and clears it when you sign out or uninstall.

**Send logs (Sentry), off by default.** Report a problem has a **Send logs** option. It is off every time you open Report a problem, and nothing is sent unless you turn it on and tap **Send logs now**. Then the watch sends one report to [Sentry](https://sentry.io), the error service the developer uses, which processes it for the developer: the same details as the QR code (watch model, Android and app versions, assistant button, network, the app's state, where the report was made from, the latest error's trace) plus the whole kept log (up to 60 lines), and a short Log ID that is also added to your email or issue so the developer can match them. It never includes message text, Bot names, account or sign-in identifiers, tokens or server addresses. The watch sets no user or IP address field and asks Sentry not to take one from the connection. The developer's Sentry project is set not to store IP addresses and to remove any location (city, region, country) Sentry would otherwise work out from the connection. Sentry keeps reports for up to 30 days on the developer's plan. Builds made without a Sentry project don't show the option.

When a Bot replies after you have left the app, the watch shows a notification with the Bot's name and the start of its reply. On the lock screen it shows only "New reply". Opening that chat removes it, and so does reading it on another device, when the watch hears of it. A Bot that needs your OK, or that has run out of usage, gets a notification the same way, which reads "New notice" on the lock screen.

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
