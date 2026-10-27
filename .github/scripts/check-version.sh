#!/usr/bin/env bash
# Decides whether this push is a new release.
#
# A release is published only when versionName in app/build.gradle.kts has no
# v<versionName> tag yet. versionCode must also go up, or Android won't install
# the update over the previous one.
#
# Writes version, tag and release (true/false) to $GITHUB_OUTPUT.
set -euo pipefail

gradle=app/build.gradle.kts
name=$(grep -m1 -oP 'versionName\s*=\s*"\K[^"]+' "$gradle" || true)
code=$(grep -m1 -oP 'versionCode\s*=\s*\K[0-9]+' "$gradle" || true)
if [ -z "$name" ] || [ -z "$code" ]; then
  echo "::error title=Version missing::Set versionCode = <number> and versionName = \"x.y.z\" in $gradle"
  exit 1
fi
tag="v$name"
out="${GITHUB_OUTPUT:-/dev/stdout}"
echo "version=$name" >> "$out"
echo "tag=$tag" >> "$out"
echo "code=$code" >> "$out"

# Older releases were tagged v<version>-build.<n>; those count as released too.
if [ -n "$(git tag --list "$tag" "$tag-build.*")" ]; then
  echo "::notice title=No new release::$tag is already released. Bump versionName and versionCode in $gradle to publish a new version."
  echo "release=false" >> "$out"
  exit 0
fi

# Highest versionCode any earlier release used: a literal in its build.gradle.kts,
# or for releases from before fixed versions, the build number in its tag
# (v1.2.1-build.21 -> 21, v0.1.7 -> 7).
max_code=0
max_tag=""
for t in $(git tag --list 'v*'); do
  c=$(git show "$t:$gradle" 2>/dev/null | grep -m1 -oP 'versionCode\s*=\s*\K[0-9]+' || true)
  if [ -z "$c" ]; then
    c=$(echo "$t" | grep -oP -- '-build\.\K[0-9]+$' || true)
    # Plain run-number tags like v0.1.7; skip version-only alias tags (v1.2.1).
    [ -z "$c" ] && ! git tag --points-at "$t" | grep -q -- '-build\.' && c=$(echo "$t" | grep -oP '[0-9]+$')
  fi
  if [ -n "$c" ] && [ "$c" -gt "$max_code" ]; then max_code=$c; max_tag=$t; fi
done
if [ "$code" -le "$max_code" ]; then
  echo "::error title=Bump versionCode::versionCode is $code but $max_tag already used $max_code. Set it to $((max_code + 1)) or higher in $gradle."
  exit 1
fi

# Every release needs a written CHANGELOG.md entry: "## <version>" (or "## v<version> (date)").
entry=$(awk -v v="$name" '
  /^## / { if (on) exit; h = $0; sub(/^## +\[?v?/, "", h); sub(/[] (].*$/, "", h); if (h == v) { on = 1; next } }
  on && NF { n++ }
  END { print n + 0 }
' CHANGELOG.md 2>/dev/null || echo 0)
if [ "$entry" -eq 0 ]; then
  echo "::error title=Changelog missing::Add a \"## $name\" section to CHANGELOG.md describing what changed, then push again."
  exit 1
fi

echo "::notice title=New release::$tag (versionCode $code)"
echo "release=true" >> "$out"
