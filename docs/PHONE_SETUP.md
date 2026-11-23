# Phone setup for the positioning trial

Instructions for the Samsung Galaxy S22 Ultra and S23 Ultra (One UI). Menu names can differ
slightly between One UI versions; use the search icon in Settings if a menu isn't where described.

There are two ways to put the test app on a phone:

- **A. Install from a file**: no laptop or cable needed. Use this normally.
- **B. Install over USB with adb**: optional, for when Claude needs the phone's logs to debug a crash.

---

## Part 1: prepare each phone (once, about 5 minutes)

1. **Install Google Play Services for AR** (ARCore, the phone's motion tracking).
   1. Open **Play Store**.
   2. Search for **Google Play Services for AR**.
   3. Tap **Install** (or **Update** if it's already there).
2. **Check there's free space**: test sessions take roughly 5–20 MB per 15 minutes.

## Part 2A: install the app from a file

1. Get the file **`app-debug.apk`**. Claude sends it in the chat, or you can copy it from
   `android/app/build/outputs/apk/debug/` after a build.
2. On the phone, tap the file (in the Claude app, Downloads, or My Files).
3. Android says it can't install apps from this source. Tap **Settings**, turn on
   **Allow from this source**, then go back.
4. Tap **Install**.
5. If **Play Protect** warns about an unknown or unsafe app, tap **More details** → **Install anyway**.
   This is normal for a test build that doesn't come from the Play Store.
6. Open **Geger Trial**.
   - When it asks for the **camera**, tap **While using the app**.
   - When it asks for **physical activity** (the step counter), tap **Allow**.
   - When it asks for **location**, choose **Precise** and **While using the app**. (Android needs
     this to let the app hear Bluetooth beacons and Wi-Fi; the app keeps no record of who you are.)
   - When it asks for **nearby devices**, tap **Allow** (Bluetooth beacons and Wi-Fi ranging).
   - If it asks to install or update Google Play Services for AR, accept.
7. Keep **Bluetooth, Wi-Fi and Location** switched on during recordings. The setup screen shows a
   **Radios** line; if it lists a problem, tap **Grant** or switch the radio on.
8. Optional but recommended: Android only allows 4 Wi-Fi scans every 2 minutes. To lift that limit,
   turn on **Developer options** (see Part 2B, steps 1–3), then in **Developer options** turn off
   **Wi-Fi scan throttling**.

To update to a newer build later, repeat steps 2–5. Your recorded sessions are kept.

## Part 2B (optional): install over USB with adb

### Turn on USB debugging (on the phone, once)

1. Open **Settings** → **About phone** → **Software information**.
2. Tap **Build number** seven times, then enter your PIN. You'll see "Developer mode has been turned on".
3. Go back to **Settings**. A new **Developer options** menu appears at the bottom; open it.
4. Turn on **USB debugging**.

### Install adb (on the laptop, once)

1. Download **SDK Platform-Tools** for your system (Windows, Mac or Linux) from
   <https://developer.android.com/tools/releases/platform-tools>.
2. Unzip it somewhere easy, e.g. `C:\platform-tools` or `~/platform-tools`.
3. **Windows only:** if step 3 of the next section shows no device, install the
   **Samsung Android USB Driver** from <https://developer.samsung.com/android-usb-driver>.

### Connect and install

1. Plug the phone into the laptop with a USB cable that carries data (not a charge-only cable).
2. On the phone, a prompt asks **Allow USB debugging?** Tick **Always allow from this computer**
   and tap **Allow**.
3. Open a terminal in the platform-tools folder and check the phone is listed.
   - **Windows:** open the folder, click the address bar, type `cmd`, press Enter, then run:
     ```
     adb devices
     ```
   - **Mac/Linux:** run:
     ```
     ./adb devices
     ```

   You should see one line ending in `device`. If it says `unauthorized`, accept the prompt on
   the phone.
4. Install the app:

   ```
   adb install -r path/to/app-debug.apk
   ```

   On Mac/Linux, write `./adb` instead of `adb`.
5. **If Claude asks for logs after a crash:**

   ```
   adb logcat -d > phone-log.txt
   ```

   Then share `phone-log.txt`.

---

## Part 3: getting recordings off the phone

In the app, open **Sessions**, pick a session and tap **Export**. Android's share sheet opens.
Send the zip to yourself with Google Drive, Gmail, or Quick Share to your laptop. Each zip holds
the session log, the map used, and the settings it ran with.
