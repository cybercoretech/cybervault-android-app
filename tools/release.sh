#!/usr/bin/env bash
# Build, sign and publish a CyberVault release from this machine.
#
#   tools/release.sh 0.1.0
#
# The release key never leaves Blackbox (~/Vaults/android/cybervault-release.*),
# so a compromised GitHub account or workflow can't sign an update. The
# script refuses to run unless the checkout is a clean, up-to-date `main`,
# then:
#   1. runs the Rust and Gradle checks,
#   2. builds the release APK with versionName X.Y.Z and versionCode X*10^6+Y*10^3+Z,
#   3. verifies the signing certificate against the pinned SHA-256 and that
#      the APK requests no INTERNET permission,
#   4. creates tag vX.Y.Z on HEAD and a GitHub release with cybervault.apk,
#      its .sha256 and INSTALL.txt.
set -euo pipefail

REPO="cybercoretech/cybervault-android-app"
CERT_SHA256="9dfa3699b793ad16af999b2fbe99b4b13ee26f8014705cc661521ee2b15e52cb"
KEY_ENV="${CYBERVAULT_KEY_ENV:-$HOME/Vaults/android/cybervault-release.env}"
SDK="${ANDROID_HOME:-$HOME/android}"
BUILD_TOOLS="$SDK/build-tools/36.0.0"

die() { printf '✖ %s\n' "$*" >&2; exit 1; }
say() { printf '▸ %s\n' "$*"; }

version="${1:-}"
[[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "usage: tools/release.sh X.Y.Z"
major=$((10#${BASH_REMATCH[1]})); minor=$((10#${BASH_REMATCH[2]})); patch=$((10#${BASH_REMATCH[3]}))
(( minor <= 999 && patch <= 999 )) || die "MINOR and PATCH must be at most 999"
code=$((major * 1000000 + minor * 1000 + patch))
tag="v$version"

cd "${CYBERVAULT_REPO_DIR:-$(dirname "$0")/..}"
[[ "$(git branch --show-current)" == main ]] || die "release from main only"
git fetch -q origin
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists"
[[ -f "fastlane/metadata/android/en-US/changelogs/$code.txt" ]] \
  || die "missing fastlane/metadata/android/en-US/changelogs/$code.txt"
[[ -r "$KEY_ENV" ]] || die "signing env not found: $KEY_ENV"

say "Checks (rust + gradle)…"
(cd rust && cargo fmt --check && cargo clippy --all-targets --locked -q -- -D warnings && cargo test --locked -q >/dev/null)
./gradlew -q testDebugUnitTest lint

say "Building signed $tag (versionCode $code)…"
set -a
# shellcheck source=/dev/null
. "$KEY_ENV"
set +a
./gradlew -q -PcybervaultVersionName="$version" -PcybervaultVersionCode="$code" clean assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
[[ -f "$apk" ]] || die "no signed APK produced (signing env incomplete?)"

# Capture whole outputs before slicing: with pipefail, `tool | head -1` can
# kill the tool with SIGPIPE and abort the script silently.
signers=$("$BUILD_TOOLS/apksigner" verify --print-certs "$apk")
cert=$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$signers")
cert=${cert%%$'\n'*}
[[ "$cert" == "$CERT_SHA256" ]] || die "signed with an unexpected certificate: $cert"
perms=$("$BUILD_TOOLS/aapt2" dump permissions "$apk")
if grep -q 'android.permission.INTERNET' <<<"$perms"; then
  die "the APK requests INTERNET"
fi
badging=$("$BUILD_TOOLS/aapt2" dump badging "$apk")
badging=${badging%%$'\n'*}
[[ "$badging" == *"versionCode='$code'"* && "$badging" == *"versionName='$version'"* ]] || die "unexpected version: $badging"
say "Certificate, permissions and version verified."

dist=$(mktemp -d)
trap 'rm -rf "$dist"' EXIT
cp "$apk" "$dist/cybervault.apk"
(cd "$dist" && sha256sum cybervault.apk > cybervault.apk.sha256)
sha=$(cut -d' ' -f1 "$dist/cybervault.apk.sha256")
cat > "$dist/INSTALL.txt" <<EOF
CyberVault $version for Android

Quick install from a Linux/macOS computer with adb and the phone on USB
(verifies the SHA-256 and the signing certificate first):
  curl -fsSL https://cybercoretech.github.io/cybervault-android-app/install.sh | sh

Manual install:
1. Download cybervault.apk on Android 11 or newer.
2. Open it and accept Android's install prompt.
3. For updates, add https://github.com/$REPO to Obtainium.

Verify:  sha256sum --check cybervault.apk.sha256
Signing certificate SHA-256: $CERT_SHA256
EOF
{
  echo "## CyberVault $version"
  echo
  echo "Signed Android 11+ APK. Offline password vault with Keysmith built in, using the same encrypted vault format as the desktop [CyberVault](https://github.com/cybercoretech/cybervault)."
  echo
  sed 's/^/> /' "fastlane/metadata/android/en-US/changelogs/$code.txt"
  echo
  echo '```sh'
  echo 'curl -fsSL https://cybercoretech.github.io/cybervault-android-app/install.sh | sh'
  echo '```'
  echo
  echo "- APK SHA-256: \`$sha\`"
  echo "- Signing certificate SHA-256: \`$CERT_SHA256\`"
  echo "- Signed on the maintainer's machine; the release key is never uploaded to GitHub."
} > "$dist/RELEASE_NOTES.md"

say "Tagging $tag and publishing…"
git tag -a "$tag" -m "CyberVault $version"
git push -q origin "$tag"
gh release create "$tag" -R "$REPO" \
  "$dist/cybervault.apk" "$dist/cybervault.apk.sha256" "$dist/INSTALL.txt" \
  --title "CyberVault $version" --notes-file "$dist/RELEASE_NOTES.md" --verify-tag
say "Released $tag: https://github.com/$REPO/releases/tag/$tag"
