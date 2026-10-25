#!/bin/sh
# Reproduce and profile Pi paging without touching the user's host or Pi session.
#
#   ./scripts/pi-paging-profile.sh [session.jsonl]
#
# The default session is the newest Pi session. A copy is placed in a temporary directory, a private host is started with that copy, and
# the real UI is driven through 20 PageUp/PageDown steps plus rapid bursts. The result and JFR recordings are left in the printed work
# directory for analysis. Set CORVI_PAGING_HW=0 to profile the software-rendering fallback instead of the production ES2 path.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PI_BIN=${PI_BIN:-$(command -v pi || true)}
if [ -z "$PI_BIN" ]; then
  echo "pi-paging-profile: pi is not on PATH (set PI_BIN)" >&2
  exit 2
fi

if [ "${1:-}" ]; then
  SESSION=$1
elif [ "${PI_SESSION:-}" ]; then
  SESSION=$PI_SESSION
else
  SESSION=$(find "$HOME/.pi/agent/sessions" -type f -name '*.jsonl' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-)
fi
if [ -z "${SESSION:-}" ] || [ ! -f "$SESSION" ]; then
  echo "pi-paging-profile: no Pi session file found (pass one as the first argument)" >&2
  exit 2
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/corvi-pi-paging.XXXXXX")
mkdir -p "$WORK/sessions"
cp "$SESSION" "$WORK/sessions/session.jsonl"
cat > "$WORK/shell.sh" <<SHELL
#!/bin/sh
cd "$ROOT"
exec "$PI_BIN" --session "$WORK/sessions/session.jsonl" --session-dir "$WORK/sessions" --offline
SHELL
chmod +x "$WORK/shell.sh"

RULE_ENABLED=0
if command -v hyprctl >/dev/null 2>&1; then
  hyprctl eval 'corvi_pi_paging_rule = hl.window_rule({ name = "corvi-pi-paging-ws6", match = { class = "^(org\\.testfx\\.toolkit\\.PrimaryStageApplication|scalafx\\.application\\.AppHelper3|javafx\\.stage\\.Stage|java)$" }, workspace = "6 silent", float = true, suppress_event = "activate" })' >/dev/null 2>&1 && RULE_ENABLED=1 || true
fi

cleanup() {
  if [ -f "$WORK/host.sock.owner" ]; then
    pid=$(cat "$WORK/host.sock.owner" 2>/dev/null || true)
    if [ -n "$pid" ]; then
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  if [ "$RULE_ENABLED" = 1 ]; then
    hyprctl eval 'corvi_pi_paging_rule:set_enabled(false)' >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

# forkEnv/forkArgs are build-time env reads; Mill caches their task results. Remove those two results so each profile run gets its own
# private socket and the requested rendering pipeline.
rm -f "$ROOT/out/app/test/forkEnv.json" "$ROOT/out/app/test/forkArgs.json" "$ROOT/out/app/test/allForkEnv.json"

HW=${CORVI_PAGING_HW:-1}
echo "pi-paging-profile: session=$SESSION"
echo "pi-paging-profile: work=$WORK"
echo "pi-paging-profile: hardware=$HW"
CORVI_PAGING_HW="$HW" \
CORVI_PAGING_HOST_SOCKET="$WORK/host.sock" \
PI_PAGING_OUT="$WORK" \
SHELL="$WORK/shell.sh" \
  "$ROOT/mill" --no-daemon app.test.runMain corvi.app.PiPagingProbe

echo "pi-paging-profile: result=$WORK/result.json"
echo "pi-paging-profile: app_jfr=$WORK/app.jfr host_jfr=$WORK/host.jfr"
