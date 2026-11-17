#!/usr/bin/env bash
# Build Abufon and install it on the Portal connected over adb.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.abufon.app
HOME_ACTIVITY=$PKG/.HomeActivity
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)}"

if grep -qE "portal-username|granny-username|change-me" abufon.properties; then
  echo "abufon.properties still has placeholder values. Fill it in first." >&2
  exit 1
fi

./gradlew assembleRelease --console=plain -q

# Stock Linphone would also register and grab calls. Ignore failure if it's already gone.
"$ADB" uninstall org.linphone || true

"$ADB" install -r -g app/build/outputs/apk/release/app-release.apk

# Permissions up front so the child never sees a dialog.
"$ADB" shell pm grant $PKG android.permission.CAMERA
"$ADB" shell pm grant $PKG android.permission.RECORD_AUDIO
# Lets the incoming-call screen start from the background on Android 10.
"$ADB" shell appops set $PKG SYSTEM_ALERT_WINDOW allow
# Lets Abufon turn the Portal's own brightness down at night (and back up in the morning).
"$ADB" shell appops set $PKG WRITE_SETTINGS allow

"$ADB" shell cmd package set-home-activity $HOME_ACTIVITY
"$ADB" shell dumpsys deviceidle whitelist +$PKG
# The Portal runs its screensaver on every sleep and wake, even with the screensaver switched
# off, and its own one opens the Portal home (photo slideshow) over Abufon. Abufon's replacement
# brings Abufon back and ends, so the screen switches off after the timeout outside the calling
# hours (Abufon holds it on during them).
# Undo: adb shell settings put secure screensaver_components com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
"$ADB" shell settings put secure screensaver_enabled 0
"$ADB" shell settings put secure screensaver_components $PKG/.AbufonDream

"$ADB" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
echo "Done. Abufon is the home app."
