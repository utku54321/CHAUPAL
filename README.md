# Chaupal Ludo (Android, offline remote)

Pass-and-play Ludo with a hidden dice remote. Two phones link over
Bluetooth / Wi-Fi Direct using Google Nearby Connections, so no internet is needed.

## Build the APK (pick one)

**A. GitHub (no install needed)**
1. Create a new repository on github.com and upload everything in this folder.
2. Open the repo's **Actions** tab, then the "Build APK" run (starts automatically).
3. When it turns green, download **chaupal-ludo-apk** at the bottom of that run, unzip it, and copy `app-debug.apk` to both phones.

**B. Android Studio**
1. File > Open > this folder. Let Gradle sync finish.
2. Build > Build App Bundle(s) / APK(s) > Build APK(s).
3. The APK is in `app/build/outputs/apk/debug/`.

Install on both phones (allow "Install unknown apps" when asked).

## First run on each phone
Open the app once and tap **Allow** on the Nearby devices / Location prompts.
Keep Bluetooth and Location switched on.

## Pairing
- Game phone: press and hold the **Chaupal** logo (or the **Menu** button during a match) to see its 6-digit code.
- Remote phone: open the same sheet, type that code, tap **Connect**.
- The game phone never shows anything about the connection. Reconnect is only on the remote.
