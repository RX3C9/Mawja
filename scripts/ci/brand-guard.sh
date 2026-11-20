#!/usr/bin/env bash
# Fails when a Flow-branded Kotlin declaration or file comes back (Mawja is independent; GPL credit lives in LICENSE/NOTICE/About).
set -u
cd "$(dirname "$0")/../.."
bad=$(grep -rnE '\b(class|object|interface|fun|val|var|typealias)\s+Flow[A-Z][A-Za-z0-9_]*' --include=*.kt app downloader admin downloader-api 2>/dev/null | grep -vE 'FlowRow|FlowColumn' )
files=$(find app downloader admin downloader-api -name 'Flow*.kt' -not -path '*/build/*' 2>/dev/null)
if [ -n "$bad$files" ]; then echo "brand: FAILED"; echo "$bad"; echo "$files"; exit 1; fi
echo "brand: ok"
