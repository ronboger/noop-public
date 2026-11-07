#!/usr/bin/env bash
set -euo pipefail

repo=ronboger/noop-public
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage: $0 /path/to/previous-signed.apk" >&2
  exit 2
fi
previous_apk="$1"
head="$(git rev-parse HEAD)"
if [ -n "$(git status --porcelain)" ] || [ "$(gh api "repos/$repo/commits/main" --jq .sha)" != "$head" ]; then
  echo "Publish from the clean commit currently on the public main branch." >&2
  exit 2
fi

version="$(sed -n 's/^[[:space:]]*versionName = "\([0-9.]*\)"/\1/p' android/app/build.gradle.kts)"
code="$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\)/\1/p' android/app/build.gradle.kts)"
test -n "$version" && test -n "$code"
tag="android-v$version"
apk="NOOP-v$version.apk"

./android/gradlew -p android :app:testFullDebugUnitTest
./android/gradlew -p android :app:assembleFullRelease
dist="$(mktemp -d)"
trap 'rm -rf -- "$dist"' EXIT
cp android/app/build/outputs/apk/full/release/app-full-release.apk "$dist/$apk"
Tools/verify-android-upgrade.sh "$previous_apk" "$dist/$apk"

digest="$(cd "$dist" && shasum -a 256 "$apk" | tee "$apk.sha256" | cut -d' ' -f1)"
printf '{"versionCode":%s,"versionName":"%s","apk":"%s","sha256":"%s","notes":"NOOP %s"}\n' \
  "$code" "$version" "$apk" "$digest" "$version" > "$dist/latest-android.json"

gh release create "$tag" -R "$repo" --draft --target "$head" --title "NOOP Android $version" \
  --notes "Signed Android build. Install over the previous NOOP release to keep your data."
for asset in "$apk" "$apk.sha256" latest-android.json; do
  gh release upload "$tag" "$dist/$asset" -R "$repo"
done
gh release edit "$tag" -R "$repo" --draft=false --latest
