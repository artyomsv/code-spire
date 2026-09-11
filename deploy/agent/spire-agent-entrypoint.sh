#!/bin/sh
# The agent container's entrypoint (docs/factory/RUN-TOPOLOGY.md §4.1 and §5).
#
# Runs the harness argv it is given and turns the workspace's commits into git bundles on
# $SPIRE_HANDOFF for the publisher: every $SPIRE_AUTOSAVE_SECONDS while the harness runs, and once
# more when it exits. DONE is written last. This container holds no token, so a bundle is the only
# way work leaves it — and the publisher, not this script, decides what reaches the remote.
#
# The prompt arrives in $SPIRE_PROMPT and reaches the harness on stdin from a file OUTSIDE the
# working tree, so an autosave can never commit it. It is unset before the harness starts.
#
# $SPIRE_SUMMARY names a file the harness may write one line to: what it changed, in its own words.
# It is exported by this script rather than passed in, so the path has ONE owner, and it sits beside
# the prompt OUTSIDE the working tree for the same reason -- an autosave must not commit it. The
# FINAL checkpoint uses it as the commit message; a mid-run autosave does not, because at that point
# nothing has been summarised and a stale line would describe work that is not in the commit.
#
# POSIX sh on purpose: this runs in whatever image an operator builds FROM the reference one, and
# busybox is the floor.
set -u

WORKSPACE="${SPIRE_WORKSPACE:-/workspace}"
HANDOFF="${SPIRE_HANDOFF:-/handoff}"
BASE="${SPIRE_BASE_COMMIT:?SPIRE_BASE_COMMIT is required}"
INTERVAL="${SPIRE_AUTOSAVE_SECONDS:-300}"
SCRATCH="${TMPDIR:-/tmp}/spire-agent.$$"
PROMPT_FILE="$SCRATCH/prompt"
STOP_FILE="$SCRATCH/stop"
SUMMARY_FILE="$SCRATCH/summary"
AUTOSAVE_MESSAGE="autosave: work in progress"

# The longest commit SUBJECT taken from the summary, in BYTES. Beyond this git's own tooling starts
# wrapping and the forge truncates, so a longer line is one nobody reads in full anyway. Named for
# bytes because that is what cut measures here, and pretending otherwise is how the cut below
# silently became able to split a character.
SUMMARY_MAX_BYTES=72

cd "$WORKSPACE" || exit 70
umask 077
mkdir -p "$SCRATCH" || exit 70

# Shortens to at most $2 bytes WITHOUT splitting a UTF-8 character.
#
# `cut -c` counts bytes here — BusyBox is the floor and GNU cut does the same — so cutting at the cap
# can land inside a multi-byte character and leave a lone continuation byte in the commit message,
# which git then renders as a stray glyph. A cut is on a character boundary exactly when the byte
# AFTER it is not a continuation byte (0x80-0xBF), so walk the cut left until that holds. At most
# three steps: no UTF-8 sequence is longer than four bytes.
cut_on_a_character_boundary() {
  value=$1
  max=$2
  [ "$(printf '%s' "$value" | wc -c)" -le "$max" ] && { printf '%s' "$value"; return; }
  while [ "$max" -gt 0 ]; do
    # -N1 because cut appends a newline: without it od reports TWO bytes and the case below,
    # which matches exactly two hex digits, never fires -- the bug this line was written to fix.
    next=$(printf '%s' "$value" | cut -c "$((max + 1))-$((max + 1))" | od -An -tx1 -N1 | tr -d ' \n')
    case "$next" in
      [89abAB]?) max=$((max - 1)) ;;
      *) break ;;
    esac
  done
  printf '%s' "$value" | cut -c "1-$max"
}

# The commit subject for the final checkpoint: the harness's own summary when it wrote one.
#
# It is MODEL OUTPUT on its way into a commit message that a person and the next review's model both
# read, so it is bounded rather than trusted: the first line only, carriage returns and other control
# characters removed, shortened to $SUMMARY_MAX_BYTES on a character boundary, and trimmed. An empty
# result falls back, because git refuses an empty message and a run that ends by failing to commit is
# the loss this file prevents.
final_message() {
  [ -s "$SUMMARY_FILE" ] || { printf '%s' "$AUTOSAVE_MESSAGE"; return; }
  line=$(head -n 1 "$SUMMARY_FILE" 2>/dev/null | tr -d '\000-\037')
  line=$(cut_on_a_character_boundary "$line" "$SUMMARY_MAX_BYTES")
  line=$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  if [ -n "$line" ]; then printf '%s' "$line"; else printf '%s' "$AUTOSAVE_MESSAGE"; fi
}

# Commit anything dirty, then bundle every commit since the base that no earlier bundle carried.
# The sequence number comes from the directory rather than a variable because the autosave loop
# is a subshell; the tmp-then-rename keeps a half-written file from ever matching *.bundle.
# $1 is the commit subject to use if anything is dirty; the autosave message when unset.
checkpoint() {
  message="${1:-$AUTOSAVE_MESSAGE}"
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    git add -A >/dev/null 2>&1 && git commit -q -m "$message" >/dev/null 2>&1 || true
  fi
  head="$(git rev-parse HEAD 2>/dev/null)" || return 0
  [ "$head" = "$BASE" ] && return 0
  last="$(cat "$SCRATCH/last-bundled" 2>/dev/null || true)"
  [ "$head" = "$last" ] && return 0
  n=$(( $(ls "$HANDOFF"/*.bundle 2>/dev/null | wc -l) + 1 ))
  tmp="$HANDOFF/.$n.bundle.tmp"
  if git bundle create "$tmp" "$BASE..HEAD" >/dev/null 2>&1; then
    mv "$tmp" "$HANDOFF/$n.bundle" && printf '%s' "$head" > "$SCRATCH/last-bundled"
  else
    rm -f "$tmp"
  fi
}

printf '%s' "${SPIRE_PROMPT:-}" > "$PROMPT_FILE"
unset SPIRE_PROMPT

# Exported, not merely set: the harness is a child process and reads this from its environment.
export SPIRE_SUMMARY="$SUMMARY_FILE"

# The loop is stopped by a flag and WAITED for, never killed: a kill landing inside git bundle
# would leave the last commit unbundled, which is the loss this whole file exists to prevent.
(
  elapsed=0
  while [ ! -e "$STOP_FILE" ]; do
    sleep 1
    # Re-checked AFTER the sleep, not only before it. The harness can exit during that second, and
    # a checkpoint that runs once shutdown has begun commits the final dirty files under the GENERIC
    # message — leaving the summary checkpoint a clean tree and nothing left to say. Invisible while
    # both checkpoints wrote the same constant; a defect the moment one of them carries the agent's
    # own words.
    [ -e "$STOP_FILE" ] && break
    elapsed=$((elapsed + 1))
    if [ "$elapsed" -ge "$INTERVAL" ]; then
      elapsed=0
      checkpoint
    fi
  done
) &
autosave=$!

"$@" < "$PROMPT_FILE"
status=$?

: > "$STOP_FILE"
wait "$autosave"
checkpoint "$(final_message)"
rm -rf "$SCRATCH"
: > "$HANDOFF/DONE"
exit "$status"
