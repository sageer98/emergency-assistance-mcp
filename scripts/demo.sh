#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
./mvnw "$@" -Pdemo verify
printf '\nDemo transcript: target/mcp-demo-transcript.json\n'
