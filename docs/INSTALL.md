# Install Grok Wear on your watch

## TL;DR

1. On the watch, turn on **ADB debugging** and **Wireless debugging** (in Developer options).
2. From a computer on the same Wi-Fi: `adb pair <ip>:<pairing-port>`, then `adb connect <ip>:<port>`.
3. `adb install app-release.apk` (from the [latest release](../../../releases/latest)).

The detailed steps follow. Grok Wear is not on Google Play, so you install it over Wi-Fi. You do this once; later updates install the same way, keeping your sign-in.

**You need:** a Wear OS 3+ watch with its own Wi-Fi, a Mac, Windows or Linux computer on the **same Wi-Fi network**, and about ten minutes.

## 1. Get the app and the install tool

1. Download `app-release.apk` from the [latest release](../../../releases/latest). The release notes list its SHA-256 checksum if you want to verify the download.
2. Install Android's `adb` tool:
   - **Mac:** `brew install android-platform-tools`, or download [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools).
   - **Windows / Linux:** download [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and unzip it. Run the commands below from that folder.

Check it works: `adb version` should print version 30.0.0 or newer.

## 2. Turn on wireless debugging on the watch

1. On the watch, open **Settings → About watch → Software** (or **Versions**) and tap **Software version** (or **Build number**) seven times. Developer options are now on.
2. Open **Settings → Developer options**. Turn on **ADB debugging**, then **Wireless debugging**. Choose **Always allow on this network**.

Menu names vary slightly between watch brands. See Google's guide: [Debug a Wear OS app over Wi-Fi](https://developer.android.com/training/wearables/get-started/debug-wifi).

## 3. Pair and connect (first time only)

1. On the watch, in **Wireless debugging**, tap **Pair new device**. It shows a six-digit code and an address like `192.168.1.23:37101`.
2. On the computer, run `adb pair 192.168.1.23:37101` (your address) and type the code.
3. Back on the watch's **Wireless debugging** screen, note the address shown at the top. It usually has a **different port**, like `192.168.1.23:42345`.
4. Run `adb connect 192.168.1.23:42345`. `adb devices` should now list the watch.

## 4. Install

Keep the watch screen on (tap it now and then), and run:

```sh
adb install -r --no-streaming app-release.apk
```

`Success` means it's installed. `--no-streaming` copies the whole file first, which copes better with watch Wi-Fi.

## 5. Start using it

1. Open **Grok Wear** on the watch and tap **Sign in**.
2. Scan the QR code with your phone and approve the sign-in in your browser.
3. Choose a Bot and tap **Talk**. Allow microphone access the first time.

When you're done, turn **Wireless debugging** off again. It drains the battery.

## No computer? Phone apps that can install for you

Some third-party Android phone apps can install an APK onto a Wear OS watch over Wi-Fi, using the same wireless debugging connection. Examples include **Bugjaeger** and **Wear Installer 2**. They are not made by or affiliated with this project, and we don't endorse or support them; follow each app's own instructions. You still need steps 2 and 5 above. An iPhone can't do this; use a computer instead.

## Updating

Repeat step 4 with the new APK. If the watch's address changed, run `adb connect` again first (step 3.3–3.4); no new pairing is needed. Your sign-in is kept, as long as the new APK is signed with the same key as the old one.

## Troubleshooting

- **`failed to connect` / `No route to host`:** both devices must be on the same Wi-Fi, and some office or guest networks block devices from seeing each other. Try a phone hotspot. Also try `adb kill-server` and connect again.
- **`device offline` mid-install:** watches turn Wi-Fi off to save power. Wake the watch screen, reconnect, and run the install again.
- **Port changed:** the connection port changes whenever wireless debugging restarts. Read the new one from the watch and `adb connect` again.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed copy was signed with a different key. Uninstall it first with `adb uninstall si.zya.grokwear`; this signs you out.

## Uninstall

On the watch, long-press **Grok Wear** in the app list and choose **Uninstall**, or run `adb uninstall si.zya.grokwear`. Your Grok account and chats are not affected.
