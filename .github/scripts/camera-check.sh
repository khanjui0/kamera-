#!/bin/sh
# Launch with the camera permission granted, let it scan the emulator's virtual scene for a
# while, and fail if the app died along the way.
set -x
pkg=com.kamera.bulucu
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant $pkg android.permission.CAMERA
adb logcat -c || true
adb shell am start -W -n $pkg/.MainActivity
sleep 15
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -oE 'text="[^"]+"' | head -10
adb exec-out screencap -p > screen-$1.png
adb logcat -d -s KayipBulucu:* AndroidRuntime:E | tail -40
adb shell pidof $pkg || { echo "APP DIED"; exit 1; }
