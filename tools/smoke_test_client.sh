#!/usr/bin/env bash
set -euo pipefail

# Run a real NeoForge dev client without a physical display. The opt-in CI hook opens the actual
# elder screen class and checks that all seven buttons fit in the viewport.
mkdir -p build run/client
log_file="build/client-smoke.log"

setsid xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 \
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dlivingvillages.smokeTestElderScreen=true" \
    ./gradlew runClient --no-daemon >"$log_file" 2>&1 &
client_pid=$!

cleanup() {
    kill -TERM -- "-$client_pid" 2>/dev/null || true
    sleep 2
    kill -KILL -- "-$client_pid" 2>/dev/null || true
    wait "$client_pid" 2>/dev/null || true
}
trap cleanup EXIT

has_smoke_pass_marker() {
    for candidate in "$log_file" run/client/logs/latest.log run/logs/latest.log; do
        [ -f "$candidate" ] || continue
        if grep -q 'LIVING_VILLAGES_ELDER_SCREEN_SMOKE_PASS:' "$candidate"; then
            return 0
        fi
    done
    return 1
}

has_smoke_failure_marker() {
    for candidate in "$log_file" run/client/logs/latest.log run/logs/latest.log; do
        [ -f "$candidate" ] || continue
        if grep -q 'LIVING_VILLAGES_ELDER_SCREEN_SMOKE_FAIL:' "$candidate"; then
            return 0
        fi
    done
    return 1
}

deadline=$((SECONDS + 240))
while (( SECONDS < deadline )); do
    if has_smoke_failure_marker; then
        echo "Minecraft client opened the elder panel but the layout check failed."
        tail -n 200 "$log_file" || true
        for candidate in run/client/logs/latest.log run/logs/latest.log; do
            [ -f "$candidate" ] && tail -n 200 "$candidate"
        done
        exit 1
    fi

    if has_smoke_pass_marker; then
        echo "Minecraft client created the elder panel and all seven buttons fit within the viewport."
        exit 0
    fi

    if ! kill -0 "$client_pid" 2>/dev/null; then
        echo "Minecraft client exited before the elder-panel layout smoke test passed."
        tail -n 200 "$log_file" || true
        for candidate in run/client/logs/latest.log run/logs/latest.log; do
            [ -f "$candidate" ] && tail -n 200 "$candidate"
        done
        exit 1
    fi

    sleep 2
done

echo "Minecraft client did not pass the elder-panel layout smoke test within 240 seconds."
tail -n 200 "$log_file" || true
for candidate in run/client/logs/latest.log run/logs/latest.log; do
    [ -f "$candidate" ] && tail -n 200 "$candidate"
done
exit 1
