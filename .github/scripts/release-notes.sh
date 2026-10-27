#!/usr/bin/env bash
# Prints release notes for a build: every commit since the previous release that
# changed the app itself (not the README, licence or CI), newest first.
#
#   release-notes.sh <new tag> [commit] [previous tag]
#
# commit defaults to HEAD; the previous tag defaults to the latest release tag
# before that commit. Needs a checkout with full history and tags.
set -euo pipefail

tag="$1"
ref="${2:-HEAD}"
prev="${3:-}"
repo="${GITHUB_REPOSITORY:-}"

# Paths that end up in the APK. Anything else (README, LICENSE, .github, badges)
# never makes a new release, so it never shows up here either.
app_paths=(app gradle build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat VERSION)

if [ -z "$prev" ]; then
  prev=$(git describe --tags --abbrev=0 --match 'v*' --exclude "$tag" "$ref^" 2>/dev/null || true)
fi
range="$ref"
[ -n "$prev" ] && range="$prev..$ref"

existing=()
for p in "${app_paths[@]}"; do [ -e "$p" ] && existing+=("$p"); done

changes=$(git log --no-merges --format='%H' "$range" -- "${existing[@]}" | while read -r sha; do
  # Drop a trailing version note like "(v3.2.1)" or "(1.4.0)" from the title.
  subject=$(git log -1 --format='%s' "$sha" | sed -E 's/ *\((v?[0-9]+(\.[0-9x]+)+)\)$//')
  # Keep "- " bullet lists from the body (user-facing detail); skip prose
  # explanations and Co-Authored-By / Claude-Session trailers.
  bullets=$(git log -1 --format='%b' "$sha" | awk '
    /^(Co-Authored-By|Claude-Session|Signed-off-by):/ { next }
    /^[-*] / { if (item != "") print item; item = "  - " substr($0, 3); next }
    item != "" && /^ +[^ ]/ { sub(/^ +/, ""); item = item " " $0; next }
    { if (item != "") print item; item = "" }
    END { if (item != "") print item }
  ')
  if [ -n "$bullets" ]; then
    echo "- **$subject**"
    echo "$bullets"
  else
    echo "- $subject"
  fi
done)

# A CHANGELOG.md section for this version ("## 1.2.2", "## v0.1.8 (date)",
# "## [2.4.1] - date") is written for users, so it wins over the commit list.
version="${tag#v}"
section=""
if [ -f CHANGELOG.md ]; then
  section=$(git show "$ref:CHANGELOG.md" 2>/dev/null | awk -v v="$version" '
    /^## / {
      if (on) exit
      h = $0; sub(/^## +\[?v?/, "", h); sub(/[] (].*$/, "", h)
      if (h == v) { on = 1; next }
    }
    on { print }
  ' | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}' || true)
fi

if [ -n "$section" ]; then
  echo "## What's new"
  echo
  echo "$section"
elif [ -n "$changes" ] && [ -z "$prev" ]; then
  echo "## First release"
  echo
  echo "$changes"
elif [ -n "$changes" ]; then
  echo "## What's new"
  echo
  echo "$changes"
else
  echo "No changes to the app itself in this build${prev:+ (same app as $prev)}."
fi

if [ -n "$repo" ] && [ -n "$prev" ]; then
  echo
  echo "**Full diff:** https://github.com/$repo/compare/$prev...$tag"
fi
