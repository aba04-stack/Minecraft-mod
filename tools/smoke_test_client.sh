#!/usr/bin/env bash
set -euo pipefail

# Run a real NeoForge dev client without a physical display. The timeout is expected: the
# client normally remains at the main menu, so success is defined by resource initialization.
mkdir -p build run/client
log_file="build/client-smoke.log"

setsid xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 ./gradlew runClient --no-daemon >"$log_file" 2>&1 &
client_pid=$!

cleanup() {
    kill -TERM -- "-$client_pid" 2>/dev/null || true
    sleep 2
    kill -KILL -- "-$client_pid" 2>/dev/null || true
    wait "$client_pid" 2>/dev/null || true
}
trap cleanup EXIT

has_startup_marker() {
    local marker='Loaded [0-9]+ advancements|Sound engine started|Created: [0-9]+x[0-9]+x[0-9]+ minecraft:textures/atlas/blocks\.png-atlas'
    for candidate in "$log_file" run/client/logs/latest.log run/logs/latest.log; do
        [ -f "$candidate" ] || continue
        if grep -Eq "$marker" "$candidate"; then
            return 0
        fi
    done
    return 1
}

deadline=$((SECONDS + 240))
while (( SECONDS < deadline )); do
    if has_startup_marker; then
        echo "Minecraft client reached resource initialization."
        exit 0
    fi

    if ! kill -0 "$client_pid" 2>/dev/null; then
        echo "Minecraft client exited before reaching resource initialization."
        tail -n 200 "$log_file" || true
        for candidate in run/client/logs/latest.log run/logs/latest.log; do
            [ -f "$candidate" ] && tail -n 200 "$candidate"
        done
        exit 1
    fi

    sleep 2
done

echo "Minecraft client did not reach resource initialization within 240 seconds."
tail -n 200 "$log_file" || true
for candidate in run/client/logs/latest.log run/logs/latest.log; do
    [ -f "$candidate" ] && tail -n 200 "$candidate"
done
exit 1
