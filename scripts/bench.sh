#!/usr/bin/env bash
# Measures map frame times for increasing numbers of tracks.
# Usage: scripts/bench.sh [track counts...]   (default: 100 500 1000 2000 5000)
# Results are appended to build/bench.txt. Set JAVA_OPTS=-Dprism.order=sw to force software rendering.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q installDist
LIB="build/install/c2-map-display/lib/*"
COUNTS=("${@:-100 500 1000 2000 5000}")
for n in ${COUNTS[@]}; do
    java -cp "$LIB" io.github.mustafagulturk.c2map.tools.TrackSender \
        --tracks "$n" --rate 1 --radius-km 60 --duration 40 --seed 7 > /dev/null &
    sender=$!
    build/install/c2-map-display/bin/c2-map-display --bench 20 --zoom 8.4 --zones build/bench-zones.tsv
    kill "$sender" 2> /dev/null || true
    wait "$sender" 2> /dev/null || true
done
