#!/usr/bin/env bash
#
# ADR-026 §9 evidence gate.
#
# For each pull request, reviews it TWICE through the real pipeline: once with the code-context
# provider disabled (control) and once enabled (treatment). Everything else is held constant.
#
# Both arms are FIRST reviews. State is wiped between them because ADR-019 would otherwise turn the
# second into a reconcile carrying an exclusion list -- which suppresses exactly the findings the two
# arms have to be compared on -- and the LLM idempotency claim would re-emit the first arm's stored
# result instead of spending a new call at all.
#
# The reset is VERIFIED, not assumed: `psql -c` runs multiple statements in one implicit transaction,
# so a single bad identifier silently rolls the whole thing back.
set -uo pipefail

ORCH=http://localhost:39280
PSQL="docker exec spire-postgres psql -U spire -d spire -tA"
OUT="$(cd "$(dirname "$0")" && pwd)"
REPO="${REPO:-code-spire}"

# The provider toggle IS the independent variable. Nothing else about the deployment changes between
# arms; the other context providers stay enabled in both so they cannot explain a difference.
code_provider() {
  $PSQL -c "UPDATE orchestrator.context_provider SET enabled = $1 WHERE type = 'code';" >/dev/null
}

# Every CODE context-resolution line the worker has ever logged, ANSI stripped.
all_code_lines() {
  docker logs spire-review-worker-dev 2>&1 | sed $'s/\033\\[[0-9;]*m//g' \
    | grep "Context resolution for CODE"
}

code_lines_so_far() {
  all_code_lines | wc -l | tr -d ' '
}

residue() {
  local rid="$1"
  $PSQL -c "SELECT
      (SELECT count(*) FROM orchestrator.review_status WHERE review_id='$rid')
    + (SELECT count(*) FROM orchestrator.review_event  WHERE review_id='$rid')
    + (SELECT count(*) FROM orchestrator.event_log     WHERE stream_id='$rid')
    + (SELECT count(*) FROM orchestrator.review_thread WHERE review_id='$rid')
    + (SELECT count(*) FROM orchestrator.llm_charge    WHERE review_id='$rid')
    + (SELECT count(*) FROM worker.comment_idempotency WHERE review_id='$rid')
    + (SELECT count(*) FROM worker.context_blob        WHERE review_id='$rid');"
}

reset_review() {
  local rid="$1" err
  for stmt in \
    "DELETE FROM orchestrator.review_status WHERE review_id='$rid'" \
    "DELETE FROM orchestrator.review_event  WHERE review_id='$rid'" \
    "DELETE FROM orchestrator.event_log     WHERE stream_id='$rid'" \
    "DELETE FROM orchestrator.review_thread WHERE review_id='$rid'" \
    "DELETE FROM orchestrator.llm_charge    WHERE review_id='$rid'" \
    "DELETE FROM worker.comment_idempotency WHERE review_id='$rid'" \
    "DELETE FROM worker.context_blob        WHERE review_id='$rid'" ; do
    err=$($PSQL -c "$stmt" 2>&1) || { echo "RESET FAILED: $stmt -> $err"; return 1; }
  done
  local left; left=$(residue "$rid" | tr -d ' ')
  [ "$left" = "0" ] || { echo "RESET INCOMPLETE: $left rows remain for $rid"; return 1; }
}

run_one() {
  local pr="$1" arm="$2"
  local rid="review::artyomsv/$REPO#$pr"

  reset_review "$rid" || return 1

  # Count the CODE lines already logged and keep only what appears BEYOND that count. Windowing by
  # line offset was wrong: `docker logs | wc -l` is not stable between calls, so the control arm
  # captured a line from a review run the previous DAY and reported code context in the one arm that
  # is defined by having none.
  local before; before=$(code_lines_so_far)

  local reg; reg=$(curl -s --max-time 60 -X POST "$ORCH/api/reviews/register" \
      -H 'Content-Type: application/json' \
      -d "{\"url\":\"https://github.com/artyomsv/$REPO/pull/$pr\"}")
  case "$reg" in *reviewId*) ;; *) echo "PR $pr [$arm] REGISTER FAILED: $reg"; return 1 ;; esac

  local status="" waited=0
  while [ $waited -lt 1500 ]; do
    status=$($PSQL -c "SELECT status FROM orchestrator.review_status WHERE review_id='$rid';" | tr -d ' ')
    case "$status" in completed|failed|refused|superseded) break ;; esac
    sleep 10; waited=$((waited+10))
  done

  all_code_lines | tail -n "+$((before + 1))" > "$OUT/ctx-$arm-pr$pr.log"
  REPO="$REPO" node "$OUT/capture.js" "$pr" "$arm" "$OUT"
}

# One pull request through both arms, back to back, so model drift cannot line up with the variable.
run_pair() {
  local pr="$1"
  echo "--- PR $pr : control (code context OFF) ---"
  code_provider false
  run_one "$pr" off
  echo "--- PR $pr : treatment (code context ON) ---"
  code_provider true
  run_one "$pr" on
}

# Noise floor: the SAME arm twice. Without it, "N findings appeared only with code context" has no
# scale -- two identical runs of a nondeterministic model already differ, and the gate would credit
# that difference to the variable. This is the control the control needed.
run_variance() {
  for pr in "$@"; do
    echo "--- PR $pr : baseline run A (code context OFF) ---"
    code_provider false
    run_one "$pr" offA
    echo "--- PR $pr : baseline run B (code context OFF, identical) ---"
    run_one "$pr" offB
  done
  code_provider true
  echo "=== variance baseline complete ==="
}

run_gate() {
  for pr in "$@"; do run_pair "$pr"; done
  code_provider true   # leave the deployment as it was found
  echo "=== gate run complete; code provider re-enabled ==="
}

"$@"
