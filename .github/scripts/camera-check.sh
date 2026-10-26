#!/bin/sh
# Launch with the camera permission granted, let it scan the emulator's virtual scene for a
# while, and fail if the app died along the way.
set -x
pkg=com.kamera.bulucu
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant $pkg android.permission.CAMERA
# Lie the phone flat, screen up, so the back camera faces down and scanning runs.
adb emu sensor set acceleration 0:0:9.81 || true
adb logcat -c || true
adb shell am start -W -n $pkg/.MainActivity
sleep 15
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -oE 'text="[^"]+"' | head -10
adb exec-out screencap -p > screen-$1.png
adb logcat -d -s KayipBulucu:* AndroidRuntime:E | tail -40
adb shell pidof $pkg || { echo "APP DIED"; exit 1; }

# Press the new buttons (zoom, freeze, resume, sound) and make sure nothing falls over.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -z "$b" ] && { echo "no button $1"; return; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 3
}
tap "Yakınlık"
tap "Dondur"
tap "Devam"
tap "Ses"
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -oE 'text="[^"]+"' | head -10
adb exec-out screencap -p > screen-$1-after.png
adb logcat -d -s AndroidRuntime:E | tail -20
adb shell pidof $pkg || { echo "APP DIED after buttons"; exit 1; }
