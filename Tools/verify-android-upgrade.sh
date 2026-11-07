#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 OLD.apk NEW.apk" >&2
  exit 2
fi

old_apk="$1"
new_apk="$2"
for apk in "$old_apk" "$new_apk"; do
  if [ ! -f "$apk" ]; then
    echo "APK not found: $apk" >&2
    exit 2
  fi
done

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner="$(find "$sdk/build-tools" -type f -name apksigner 2>/dev/null | sort | tail -1)"
aapt="$(find "$sdk/build-tools" -type f -name aapt 2>/dev/null | sort | tail -1)"

if [ -z "$apksigner" ] || [ -z "$aapt" ]; then
  echo "Android build-tools not found under $sdk/build-tools" >&2
  exit 2
fi

apk_field() {
  "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1|\2|\3/p"
}

cert_digest() {
  "$apksigner" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    head -1
}

IFS='|' read -r old_package old_code old_name <<<"$(apk_field "$old_apk")"
IFS='|' read -r new_package new_code new_name <<<"$(apk_field "$new_apk")"
old_cert="$(cert_digest "$old_apk")"
new_cert="$(cert_digest "$new_apk")"

echo "Old: $old_package  version=$old_name ($old_code)"
echo "     signer=$old_cert"
echo "New: $new_package  version=$new_name ($new_code)"
echo "     signer=$new_cert"

# Refuse to compare fields we failed to read. Without this, output-format drift in a future
# build-tools release would leave both digests empty, make "" != "" false, skip the signer check
# entirely and still print PASS — the one check whose silent failure costs an uninstall/restore.
for pair in "package:$old_package|$new_package" "versionCode:$old_code|$new_code" "signer:$old_cert|$new_cert"; do
  field="${pair%%:*}"
  values="${pair#*:}"
  if [ -z "${values%%|*}" ] || [ -z "${values#*|}" ]; then
    echo "FAIL: could not read $field from both APKs; refusing to report a pass." >&2
    exit 2
  fi
done

case "$old_code$new_code" in
  *[!0-9]*)
    echo "FAIL: versionCode is not numeric in one of the APKs." >&2
    exit 2
    ;;
esac

ok=1
if [ "$old_package" != "$new_package" ]; then
  echo "FAIL: application IDs differ; this is not an in-place update." >&2
  ok=0
fi
if [ "$old_cert" != "$new_cert" ]; then
  echo "FAIL: signing certificates differ; Android will reject the update." >&2
  ok=0
fi
if [ "$new_code" -le "$old_code" ]; then
  echo "FAIL: new versionCode must be greater than the installed versionCode." >&2
  ok=0
fi

if [ "$ok" -ne 1 ]; then
  exit 1
fi

echo "PASS: package, signer, and versionCode are compatible with an in-place update."
