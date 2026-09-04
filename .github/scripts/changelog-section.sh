#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading.
#
# The release notes for a tag are this section verbatim, so the changelog stays the single place a
# version is described — a release whose notes were written separately drifts from it by the second
# release.
set -euo pipefail

version="${1:?usage: changelog-section.sh <version> [changelog]}"
changelog="${2:-CHANGELOG.md}"

section=$(awk -v version="$version" '
    $0 == "## [" version "]" || index($0, "## [" version "] ") == 1 { capture = 1; next }
    # The next version heading, or the link-reference block that closes the file.
    capture && (/^## / || /^\[[^]]+\]: /) { exit }
    capture { print }
' "$changelog")

# Trim the blank lines the section boundaries leave behind.
section=$(printf '%s\n' "$section" | sed -e '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')

if [ -z "$section" ]; then
    echo "no CHANGELOG.md section for version $version" >&2
    exit 1
fi

printf '%s\n' "$section"
