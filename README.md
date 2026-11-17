# Abufon

Abufon turns a **Meta Portal** into a one-button video phone between a child and their grandmother.
The screen shows granny's photo and one big button. Tap it and the Portal calls granny; when granny
calls, the Portal rings (or answers by itself). Nobody else can call in, and the child can't reach
anything else on the device.

It is a small Kotlin Android app (Android 10, the Portal's OS) built on the
[Linphone SDK](https://www.linphone.org/). Calls use SIP: the Portal has its own SIP account, and
granny uses the free Linphone app on her phone with hers. The interface is in Spanish.

- [What it does](#what-it-does)
- [What you need](#what-you-need)
- [Put the Portal in debug mode](#put-the-portal-in-debug-mode)
- [Configure](#configure)
- [Build and install](#build-and-install)
- [Settings on the Portal](#settings-on-the-portal)
- [Screensaver and night screen](#screensaver-and-night-screen)
- [Locking the Portal to Abufon](#locking-the-portal-to-abufon)
- [Logs and call recordings](#logs-and-call-recordings)
- [Undo everything](#undo-everything)
- [Development](#development)
- [License](#license)

## What it does

- **One contact.** Only granny's SIP address can call in, and the button only calls her. Any other
  caller is declined silently.
- **Calling hours.** An adult can set when calls are allowed, plus an optional second window for
  granny's incoming calls. Outside the hours the home screen "rests" (a sleeping bird and the time
  calls open again), and granny's calls are declined without ringing and shown as a missed call.
- **Pause.** One switch stops all calls in both directions.
- **Auto-answer.** Granny's calls can be answered by themselves after a countdown.
- **Missed calls.** A letter on the home screen shows that granny called.
- **Ringing volume.** While granny's call rings, the media volume is raised to a minimum level and
  put back afterwards.
- **Encrypted calls.** Audio and video are always encrypted (SRTP by default, ZRTP optional).
- **Night screen.** Outside calling hours the screen is dimmed to almost black and allowed to
  switch off; during calling hours it stays on.
- **Replaces the Portal home.** Abufon is installed as the Portal's home app and replaces the
  Portal screensaver, so the photo slideshow never takes over.
- **Survives crashes.** If Linphone keeps crashing on start, Abufon starts in a safe mode (offline
  screen) and retries after 1, 5, 15 and 30 minutes, or when "Reintentar" is tapped.
- **Diagnostics.** A per-second call-quality log and optional local call recordings.

## What you need

- A Meta Portal running Android 10 (built and tested on a Portal in portrait and landscape,
  1280 × 800 at 160 dpi).
- A Mac or PC with:
  - JDK 17 (for example `brew install openjdk@17`)
  - Android platform tools (`adb`), for example `brew install android-platform-tools`
  - A USB-C cable that carries data
- Two SIP accounts, for example free ones from
  [subscribe.linphone.org](https://subscribe.linphone.org/):
  - one for the Portal
  - one for granny, signed in to the **Linphone** app on her phone (Android or iOS)

The Android SDK is downloaded by Gradle the first time you build. If Gradle can't find it, create
`local.properties` in the project root with `sdk.dir=/path/to/Android/sdk`.

## Put the Portal in debug mode

Abufon is installed from a computer over USB, so the Portal needs USB debugging switched on once.
The setting survives reboots, updates of Abufon, and locking the Portal to Abufon.

1. On the Portal, open **Settings** (with Abufon installed: hold the top-left corner, answer the
   sum, then **Ajustes del Portal**; see [Leaving Abufon](#leaving-abufon)).
2. Open **About** (sometimes **Device info**) and tap **Build number** 7 times, until it says you
   are a developer. The Portal may ask for its PIN.
3. Go back to Settings and open **Developer options** (often under **System**). Switch on
   **USB debugging**.
4. Connect the Portal to the computer with the USB-C cable.
5. On the computer, run:
   ```bash
   adb devices
   ```
   The Portal shows a prompt asking to allow USB debugging from this computer. Tick
   **Always allow from this computer** and tap **Allow**. Run `adb devices` again: the Portal
   should be listed as `device` (not `unauthorized`).

Menu names differ a little between Portal models and software versions. If you can't find them,
search Settings for "build number" or "developer".

Optional, to work without a cable on the same Wi-Fi (until the next reboot):

```bash
adb tcpip 5555
```

```bash
adb connect <portal-ip>:5555
```

## Configure

All call settings are build-time values in `abufon.properties` in the project root. This file holds
the Portal's SIP password and is **gitignored**; never commit it.

```bash
cp abufon.properties.example abufon.properties
```

Then edit `abufon.properties`:

| Key | Required | Default | What it does |
|---|---|---|---|
| `PORTAL_SIP` | yes | | The Portal's own SIP address, e.g. `sip:portal-username@sip.linphone.org`. |
| `PORTAL_PASSWORD` | yes | | That account's password. |
| `GRANNY_SIP` | yes | | Granny's SIP address. The only caller accepted, and the only one the button calls. |
| `GRANNY_NAME` | yes | | The name shown on screen ("Abuela llamó", the call screen, …). |
| `AUTO_ANSWER` | yes | | `true` answers granny's calls by themselves after the countdown. Starting value only: it can be changed on the Portal. |
| `AUTO_ANSWER_SECONDS` | no | `5` | Countdown before auto-answer (1–60). Starting value only: it can be changed on the Portal. |
| `CALL_TIMEOUT_SECONDS` | no | `30` | How long an outgoing call rings before showing "No contestó". |
| `MEDIA_ENCRYPTION` | no | `srtp` | `srtp`, or `zrtp` for end-to-end encryption (granny's Linphone app may ask her to confirm a short code on the first call). Calls are never unencrypted. |
| `RING_MIN_VOLUME_PERCENT` | no | `60` | While granny's call rings, media volume is raised to at least this percent and put back afterwards. `0` never touches the volume. |
| `KIOSK` | no | `false` | `true` pins Abufon with Android screen pinning, so swipes can't leave it. See [Locking the Portal to Abufon](#locking-the-portal-to-abufon). |
| `RECORD_CALLS` | no | `true` | Record every call on the Portal (audio only, both sides mixed) for diagnosing audio problems. |
| `RECORD_KEEP_DAYS` | no | `3` | Recordings older than this are deleted. The total is also kept under 1 GB. |

**Granny's photo:** put `granny_photo.jpg` (or `.png`) in the project root, next to
`abufon.properties`. Square works best, with the face in the middle. Like `abufon.properties`, it is
gitignored so it never ends up in the repository. Without it, the build uses the neutral placeholder
in `app/placeholder/` and prints a note.

Every change to `abufon.properties` or the photo needs a rebuild and reinstall.

## Build and install

With the Portal connected (`adb devices` lists it) and `abufon.properties` filled in:

```bash
./deploy.sh
```

`deploy.sh` does all of the following:

1. Refuses to run if `abufon.properties` still has the example placeholders.
2. Builds the release APK (`./gradlew assembleRelease`, signed with the local debug key).
3. Uninstalls the stock Linphone app if present, so it can't register with the same account and
   take the calls.
4. Installs Abufon and grants camera and microphone up front, so the child never sees a
   permission dialog.
5. Allows **display over other apps** (`SYSTEM_ALERT_WINDOW`), so the incoming-call screen can
   open while Abufon is in the background.
6. Allows **modify system settings** (`WRITE_SETTINGS`), so Abufon can dim the Portal at night.
7. Makes Abufon the **home app** and exempts it from battery optimisation (Doze), so it stays
   registered and reachable.
8. Replaces the Portal screensaver with Abufon's (see below).
9. Opens the home screen.

`deploy.sh` expects JDK 17 at the Homebrew path and `adb` on the `PATH`. Override with:

```bash
JAVA_HOME=/path/to/jdk17 ADB=/path/to/adb ./deploy.sh
```

To only build: `./gradlew assembleRelease`. The APK is at
`app/build/outputs/apk/release/app-release.apk`.

## Settings on the Portal

**Hold the top-left corner of the home screen for 3 seconds.** A ring appears after 1 second and
fills up. Then answer the multiplication ("¿Cuánto es 7 × 8?") on the keypad. Three wrong answers in
a row close the lock and ignore the corner for 30 seconds. The lock also closes after 30 seconds
without a touch.

| Setting | Default | What it does |
|---|---|---|
| **Pausar llamadas** | off | No calls in or out until it is switched off. |
| **Horario de llamadas** | off (calls allowed all day) | The calling window, in the Portal's local time, for calls in both directions. Default window when switched on: 13:00 to 01:00. Outside it the screen rests and dims. |
| **Otro horario para sus llamadas** | off | A second window that applies only to granny's incoming calls (only while the calling window is on). Default 13:00 to 20:00. |
| **Contestar automáticamente** | from `AUTO_ANSWER` | Granny's calls answer themselves after the countdown. |
| Countdown | from `AUTO_ANSWER_SECONDS` | 1–60 seconds. |
| **Pío al poder llamar** | on | A chirp when calling hours begin. |
| **Salir de Abufon** | | Leave Abufon: **Inicio del Portal** opens the Portal home, **Ajustes del Portal** opens the Portal settings. |

These settings are stored on the Portal and kept across updates of Abufon. A window whose end is
earlier than its start runs past midnight; equal start and end means all day.

### Leaving Abufon

**Salir de Abufon** in the settings stops screen pinning (if on) and opens the Portal home or
settings. Abufon is still the home app, so it comes back when the Home button is pressed or the
Portal restarts. Granny's calls still come in while you are outside Abufon.

With a computer, you can also open Android settings directly:

```bash
adb shell am start -a android.settings.SETTINGS
```

## Screensaver and night screen

The Portal runs its screensaver on every sleep and wake, **even when the screensaver is switched
off** in settings, and the Portal's screensaver opens the Portal home (photo slideshow) on top of
Abufon. So `deploy.sh` replaces it with Abufon's own screensaver:

```bash
adb shell settings put secure screensaver_enabled 0
```

```bash
adb shell settings put secure screensaver_components com.abufon.app/.AbufonDream
```

Abufon's screensaver has no window: it brings Abufon to the front (unless a call is on) and ends
immediately. The result:

- **During calling hours** (or all day if the schedule is off), Abufon keeps the screen on.
- **Outside calling hours** (and while calls are paused), Abufon dims the screen to almost black and lets it switch off after
  the Portal's normal screen timeout (5 minutes by default). Waking it (touch, button, or the
  Portal's presence sensor when someone walks by) shows Abufon, still dimmed.
- To avoid a bright flash on wake, Abufon also turns the Portal's own system brightness down to
  minimum at night (manual mode). When calling hours start or calls are unpaused, it puts back the
  brightness and mode it found. If you change the Portal's brightness during the day, that is the
  value it returns to.

If the Portal ever stays dim during the day:

```bash
adb shell settings put system screen_brightness 255
```

To change the screen timeout (milliseconds; 300000 = 5 minutes):

```bash
adb shell settings put system screen_off_timeout 300000
```

To go back to the Portal's own screensaver, see [Undo everything](#undo-everything).

## Locking the Portal to Abufon

There are two levels:

1. **Home app (default, set by `deploy.sh`).** Abufon replaces the Portal home and screensaver.
   Home, restarts and wake-ups all lead back to Abufon. Swipes from the screen edges can still show
   the Portal's system bars.
2. **Screen pinning (`KIOSK=true`, then rebuild and deploy).** Abufon also pins itself with
   Android's lock task mode, so swipes and buttons can't leave it. Without a device-owner setup,
   Android asks for confirmation each time it pins. **Salir de Abufon** unpins it. With a
   computer:

   ```bash
   adb shell am task lock stop
   ```

USB debugging stays on in both modes, so a computer can always get in.

## Logs and call recordings

Everything stays on the Portal. Nothing is uploaded.

**Call log:** `Android/data/com.abufon.app/files/calls.log` (rolled to `calls.1.log` at 1 MB). Once
a second during a call it writes audio and video bitrates, loss, jitter, round trip, microphone and
speaker levels, mute flags and volume; lines where audio stops in one direction are marked `!!`. It
also logs call, registration, schedule, screensaver and brightness events. No SIP addresses are
written. It survives reboots (unlike logcat).

```bash
adb pull /sdcard/Android/data/com.abufon.app/files/calls.log
```

**Recordings** (`RECORD_CALLS=true`): one `.wav` per call, both sides mixed, in
`Android/data/com.abufon.app/files/recordings/`, named `call_YYYYMMDD_HHMMSS.wav`. Deleted after
`RECORD_KEEP_DAYS` days and kept under 1 GB in total. Consider telling the people on the call that
calls are recorded.

```bash
adb pull /sdcard/Android/data/com.abufon.app/files/recordings/
```

**Live Android log:**

```bash
adb logcat -s Abufon
```

## Undo everything

Put the Portal home and screensaver back, and remove Abufon:

```bash
adb shell cmd package set-home-activity com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeActivity
```

```bash
adb shell settings put secure screensaver_components com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
```

```bash
adb shell settings put system screen_brightness 255
```

```bash
adb uninstall com.abufon.app
```

Switch off USB debugging in **Developer options** if you no longer need it.

## Development

- **Code:** `app/src/main/java/com/abufon/app/`. Start with `HomeActivity` (home screen and its
  states), `LinphoneManager` (SIP and calls), `Settings` and `Schedule` (the parent's rules),
  `CallActivity` and `IncomingCallActivity`.
- **Unit tests** (schedule rules, home-screen states, voice level):

  ```bash
  ./gradlew test
  ```

- **Layouts** in `res/layout*/screen_*.xml` are generated from the designer's coordinates by
  `tools/gen_layouts.py`; edit the script, not the XML. It needs the designer's asset folders,
  which are not in this repository.
- **Previews** (debug builds only, installed with `./gradlew installDebug`): any screen can be
  shown without a call, for screenshots, e.g.

  ```bash
  adb shell am start -n com.abufon.app/.HomeActivity --es preview offline --es orient land
  ```

  The preview names are listed in `HomeActivity`.
- **Stack:** Kotlin 1.9, Android Gradle Plugin 8.5, compile SDK 34, target and minimum SDK 29
  (Android 10), Linphone SDK 5.5.39, ARM only (`arm64-v8a`, `armeabi-v7a`).

## License

The Abufon source code is under the [MIT License](LICENSE): free to use, change and fork, as long
as the copyright notice is kept.

The app links the Linphone SDK, which is licensed under the
[GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) (or a commercial licence from Belledonne
Communications). A built Abufon APK is therefore covered by the GPLv3: anyone who distributes the
APK must make its full source available under the GPLv3.

The Fredoka font is licensed under the SIL Open Font License; see `FREDOKA_OFL.txt`.
