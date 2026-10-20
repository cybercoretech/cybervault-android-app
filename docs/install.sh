#!/bin/sh
# CyberVault for Android — install the latest signed release over adb.
#
#   curl -fsSL https://cybercoretech.github.io/cybervault-android-app/install.sh | sh
#
# Downloads cybervault.apk and its .sha256 from the latest GitHub release,
# verifies the checksum, then `adb install -r`s it on the connected phone.
# With several devices attached, set ANDROID_SERIAL to choose one; the
# script never guesses. Set CYBERVAULT_VERSION=v0.1.0 to pin a release.
set -eu

REPO="cybercoretech/cybervault-android-app"
# SHA-256 of the CyberVault release signing certificate (also in the README
# and on the site). Checked whenever apksigner is available.
CERT_SHA256="9dfa3699b793ad16af999b2fbe99b4b13ee26f8014705cc661521ee2b15e52cb"
VERSION="${CYBERVAULT_VERSION:-latest}"

say() { printf '\033[36m▸\033[0m %s\n' "$*"; }
die() { printf '\033[31m✖ %s\033[0m\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v adb >/dev/null 2>&1 || die "adb is required (Android platform-tools): https://developer.android.com/tools/releases/platform-tools"
if command -v sha256sum >/dev/null 2>&1; then
  sha() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  die "sha256sum or shasum is required to verify the download"
fi

if [ "$VERSION" = latest ]; then
  base="https://github.com/$REPO/releases/latest/download"
else
  base="https://github.com/$REPO/releases/download/$VERSION"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

say "Downloading CyberVault ($VERSION)…"
curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/cybervault.apk" "$base/cybervault.apk" \
  || die "download failed: $base/cybervault.apk"
curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/cybervault.apk.sha256" "$base/cybervault.apk.sha256" \
  || die "checksum download failed"

expected=$(cut -d' ' -f1 "$tmp/cybervault.apk.sha256")
actual=$(sha "$tmp/cybervault.apk")
[ -n "$expected" ] && [ "$expected" = "$actual" ] \
  || die "SHA-256 mismatch: expected $expected, got $actual. Not installing."
say "SHA-256 verified: $actual"

signer=""
for c in apksigner "${ANDROID_HOME:-$HOME/android}"/build-tools/*/apksigner; do
  if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then signer=$c; fi
done
if [ -n "$signer" ]; then
  cert=$("$signer" verify --print-certs "$tmp/cybervault.apk" 2>/dev/null \
    | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  [ "$cert" = "$CERT_SHA256" ] || die "signing certificate mismatch (got ${cert:-none}). Not installing."
  say "Signing certificate verified: CyberVault release key"
else
  say "apksigner not found: skipping the certificate check (Android still enforces the key on updates)"
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
  devices=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  count=$(printf '%s\n' "$devices" | grep -c . || true)
  unauthorized=$(adb devices | awk 'NR > 1 && $2 == "unauthorized"' | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    [ "$unauthorized" -gt 0 ] && die "the phone hasn't authorized this computer yet: unlock it and accept the USB debugging prompt"
    die "no device found: connect the phone over USB and turn on USB debugging"
  fi
  [ "$count" -gt 1 ] && die "$count devices connected; choose one with ANDROID_SERIAL=<serial> (see 'adb devices')"
  ANDROID_SERIAL=$devices
  export ANDROID_SERIAL
fi

model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
say "Installing on ${model:-device} ($ANDROID_SERIAL)…"
adb install -r "$tmp/cybervault.apk" >/dev/null || die "adb install failed (an older build signed with a different key must be uninstalled first)"
say "Done. Open CyberVault on your phone and create your vault."
