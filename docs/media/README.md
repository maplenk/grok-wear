# Launch media

Captured from Grok Wear 0.4.1 on a round 454 × 454 Wear OS emulator (Android Studio's "Wear OS Large Round" profile, API 36). The debug build's preview mode supplies synthetic demo Bots and messages, so no account, names or real content appear.

| File | Shows |
| --- | --- |
| `bot-list.png` | Bot list: a pumpkin Bot working, an unread badge |
| `review-before-send.png` | Review before sending a text message |
| `send-message.mp4` (13 s) | Open a Bot, Message, review, Send, then Sending…, Sent and the Bot's reply |
| `talk-listening.mp4` (10 s) | Talk, then Listening and live captions |

Recorded with `adb shell screenrecord`, then held on the last frame and converted to constant 30 fps H.264.

In the preview, typing is skipped (the review screen opens with demo text), and sending and the call are simulated. The call reaches Listening 1.3 s after Talk, matching the measured warm time on the emulator; replies and captions are demo text.
