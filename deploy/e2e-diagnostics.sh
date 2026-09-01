#!/usr/bin/env bash
# Everything a failed e2e run needs and cannot reconstruct afterwards.
#
#   ./deploy/e2e-diagnostics.sh [output-directory]
#
# Without this a nightly failure is a red square. The ordering below is the runbook's own: when the
# bot goes silent, check the PLUMBING before the POLICY. A dead webhook, a rejected secret and a
# legitimate policy decline all look identical from our side — nothing arrives, so nothing is logged —
# and the only record of the first two is at GitLab's end, which is why the hook list is captured here
# rather than left to whoever reads the logs.
set -uo pipefail

OUT="${1:-e2e-diagnostics}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/deploy/compose.yml" -f "$ROOT/deploy/compose.e2e.yml"
         --env-file "$ROOT/deploy/.env")

mkdir -p "$OUT"

echo "== container state =="
"${COMPOSE[@]}" ps -a > "$OUT/ps.txt" 2>&1
cat "$OUT/ps.txt"

echo "== service logs =="
for service in gateway orchestrator worker ui gitlab llm-mock; do
    "${COMPOSE[@]}" logs "$service" --since 60m > "$OUT/$service.log" 2>&1
done

# What the worker actually sent the model, and what it got back. The only window into a review's
# inside, and the reason PromptLog does not have to be enabled for a test.
echo "== llm-mock journal =="
if ! curl -fsS --max-time 30 "http://localhost:${E2E_LLM_MOCK_PORT:-34881}/__admin/requests" \
        > "$OUT/llm-mock-journal.json" 2>/dev/null; then
    echo "WARNING: could not read the llm-mock journal" | tee "$OUT/llm-mock-journal.json"
fi

# GitLab's own delivery log. A rejected secret or an unreachable target shows ONLY here.
#
# The token must match what the harness actually mints. It previously named a `root` token that does
# not exist — no root user is bootstrapped at all — so every call below answered 401, the project list
# came back empty, the loop never ran, and the artifact looked complete while missing the one thing it
# exists to capture.
echo "== gitlab webhook deliveries =="
GITLAB="http://localhost:${E2E_GITLAB_PORT:-34880}"
TOKEN="${E2E_GITLAB_ADMIN_TOKEN:-TEST-e2e-admin-token-00000000000}"   # GitLabDriver.ADMIN_TOKEN

# Anchored to the object boundary: a bare '"id":[0-9]*' also matches namespace.id, owner.id and
# forked_from_project.id, so the loop would fetch hooks for ids that are not projects.
projects="$(curl -fsS --max-time 30 -H "PRIVATE-TOKEN: $TOKEN" \
    "$GITLAB/api/v4/projects?owned=true&per_page=100" 2>/dev/null \
    | grep -o '{"id":[0-9]*' | cut -d: -f2)"

if [ -z "$projects" ]; then
    # Loud, not silent. `set -uo pipefail` does not fail an empty `for`, so without this the artifact
    # simply lacks the delivery log and nothing says why.
    echo "WARNING: GitLab returned no projects — the token did not authenticate, or GitLab is down."\
" No webhook delivery log was captured." | tee "$OUT/gitlab-hooks-MISSING.txt"
else
    for project in $projects; do
        if ! curl -fsS --max-time 30 -H "PRIVATE-TOKEN: $TOKEN" \
                "$GITLAB/api/v4/projects/$project/hooks" \
                > "$OUT/gitlab-hooks-$project.json" 2>/dev/null; then
            echo "WARNING: could not read hooks for project $project" \
                | tee "$OUT/gitlab-hooks-$project.json"
        fi
    done
fi

echo
echo "diagnostics written to $OUT"
