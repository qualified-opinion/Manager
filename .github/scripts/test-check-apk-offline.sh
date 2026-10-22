#!/usr/bin/env bash
# Generate real APK manifests with the same SDK/build-tools installed by Android CI.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory" >&2
  exit 1
fi
export AAPT="${AAPT:-$sdk_root/build-tools/36.0.0/aapt}"
aapt2="$sdk_root/build-tools/36.0.0/aapt2"
android_jar="$sdk_root/platforms/android-36/android.jar"
if [[ ! -x "$AAPT" || ! -x "$aapt2" || ! -f "$android_jar" ]]; then
  echo "Android platform 36 and build-tools 36.0.0 are required" >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

# These are manifest-only test APKs; no fixtures or app permission changes are committed.
for fixture in clean internet; do
  permission=""
  if [[ "$fixture" == internet ]]; then
    permission='<uses-permission android:name="android.permission.INTERNET" />'
  fi
  cat > "$fixture_dir/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.janreins.offlinecheck.$fixture">
    $permission
    <application />
</manifest>
EOF
  "$aapt2" link -I "$android_jar" --manifest "$fixture_dir/AndroidManifest.xml" \
    -o "$fixture_dir/$fixture.apk"
done

failures=0
assert_check() {
  local label="$1" expected="$2" diagnostic="$3" apk="$4"
  local status=0
  "$script_dir/check-apk-offline.sh" "$apk" > "$fixture_dir/output.txt" 2>&1 || status=$?
  cat "$fixture_dir/output.txt"
  if [[ "$expected" == fail && "$status" -eq 0 ]] ||
     [[ "$expected" == pass && "$status" -ne 0 ]]; then
    echo "FAIL: $label (expected $expected, checker exit $status)" >&2
    failures=$((failures + 1))
  elif ! grep -Fq -- "$diagnostic" "$fixture_dir/output.txt"; then
    echo "FAIL: $label (missing expected diagnostic: $diagnostic)" >&2
    failures=$((failures + 1))
  else
    echo "PASS: $label (checker exit $status)"
  fi
}

assert_check "missing APK is rejected" fail "APK not found:" "$fixture_dir/missing.apk"
assert_check "INTERNET permission is rejected" fail "Offline policy violated:" "$fixture_dir/internet.apk"
assert_check "clean APK is accepted" pass "requests no network permission" "$fixture_dir/clean.apk"

if [[ "$failures" -ne 0 ]]; then
  echo "Offline-check tests failed: $failures" >&2
  exit 1
fi
echo "All offline-check tests passed"
