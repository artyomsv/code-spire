import { n, e, g } from '../dsl.mjs';

const X = [348, 538, 728], Y = [130, 250, 370], PW = 170, PH = 80;

export default {
  slug: 'agent-images',
  title: 'spire-agent-codex, spire-agent-claude-code',
  short: 'The agent images',
  kicker: 'The agent images. Each one runs a coding CLI in a box with no way to push.',
  summary: 'The two images are the same shape: Node, git, one harness CLI, and one shared entrypoint script. They follow a published contract, so another agent image can take their place.',
  size: [1440, 730],
  groups: [
    g('ag', 330, 96, 586, 372, 'Agent image', { subject: true }),
  ],
  nodes: [
    n('rw', 'service', 40, 130, 190, 320, 'spire-run-worker', ['sets the command', 'and the env,', 'reads stdout']),

    n('entry', 'part', X[0], Y[0], PW, PH, 'Entrypoint script', ['the prompt goes to stdin,', 'never to argv']),
    n('cli', 'part', X[1], Y[0], PW, PH, 'Harness CLI', ['codex exec', 'or claude -p']),
    n('git', 'part', X[2], Y[0], PW, PH, 'git', ['commits in', '/workspace']),
    n('save', 'part', X[0], Y[1], PW, PH, 'Autosave', ['a bundle every 300 s', 'and one at exit']),
    n('art', 'part', X[1], Y[1], PW, PH, 'Artifact copy', ['artifact.md,', 'step-summary.json']),
    n('done', 'part', X[2], Y[1], PW, PH, 'DONE marker', ['written last']),
    n('lab', 'part', X[0], Y[2], PW, PH, 'Image labels', ['toolchain, harness,', 'models']),

    n('inp', 'store', 1030, 124, 170, 84, '/inputs', ['read-only, reviews']),
    n('ws', 'store', 1030, 240, 170, 84, '/workspace', ['the clone']),
    n('hand', 'store', 1030, 356, 170, 84, '/handoff', ['what leaves the box']),
    n('peer', 'service', 1030, 510, 170, 80, 'Its only peer', ['spire-model:8080 or', 'spire-egress:8888']),

    n('note', 'note', 40, 520, 0, 0, 'The Java half lives in the run worker', [
      'spire-harness-codex and spire-harness-claude-code build the command line,',
      'declare the hosts and parse the transcript (sheet 04).',
      'spire-agent-image is a command-line tool that checks an image against the contract.',
    ]),
  ],
  edges: [
    e('rw', 'entry', 'sync', 'command, env', { ao: -120 }),
    e('entry', 'cli', 'sync'),
    e('cli', 'git', 'sync'),
    e('ag', 'rw', 'sync', 'stdout: transcript', { ao: 88, bo: 80 }),
    e('inp', 'ag', 'file', 'read', { bo: -116 }),
    e('ag', 'ws', 'file', '', { ao: 0, both: true }),
    e('ag', 'hand', 'file', 'bundles', { ao: 116 }),
    e('ag', 'peer', 'sync', 'model calls', { as: 'b', bs: 'l' }),
  ],
  sections: [
    { h: 'What an agent image is responsible for', items: [
      'It runs the harness CLI over `/workspace` with the prompt from `SPIRE_PROMPT`.',
      'It writes each commit as a git bundle to `/handoff`, every 300 seconds and once at exit.',
      'It copies the answer of a review or a draft (`artifact.md`) and the step summary to `/handoff`.',
      'It writes `DONE` last, and exits with the exit code of the harness.',
    ] },
    { h: 'What it never has', items: [
      'A git token. It cannot push.',
      'On a proxied run, a real model credential. It has `TEST-PLACEHOLDER`.',
      'A direct route to the internet, when the egress mode is not `off`. Its network is then internal, and the only way out is a proxy. With the egress mode `off` the agent has a direct route.',
    ] },
    { h: 'The two images', wide: true, head: ['', '`spire-agent-codex`', '`spire-agent-claude-code`'], rows: [
      ['CLI', '`@openai/codex` 0.156.1', '`@anthropic-ai/claude-code` 2.1.319'],
      ['Used for', 'Reviews, builds, fixes, drafts', 'Reviews only (`harness_reviews_only`)'],
      ['Paid with', 'An OpenAI API key, or a Codex subscription seat', 'An Anthropic API key only. A subscription is refused.'],
      ['Without the model proxy', 'Allowed. The agent then holds the key.', 'Refused (`credential_proxy_required`).'],
      ['Hosts', '`api.openai.com` or `chatgpt.com`', '`api.anthropic.com`'],
      ['Main flags', '`codex exec --json --sandbox danger-full-access --skip-git-repo-check`', '`claude -p --output-format stream-json --bare --safe-mode --strict-mcp-config --disable-slash-commands --setting-sources user`'],
      ['Model list', 'Baked into a label at build time by `build-codex.sh`', 'From `models.json`'],
      ['Default', 'Built and configured', 'Opt-in: `SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE`, default `none`'],
    ] },
    { h: 'The agent image contract', items: [
      'An entrypoint that runs the command it is given. User 1001, not root. `/bin/sh`, `git` and CA certificates are present.',
      '`/workspace` and `/handoff` exist and the run user can write to them.',
      'The prompt reaches the harness on stdin. `SPIRE_BASE_COMMIT` is required.',
      'Each commit leaves as a `*.bundle` file. `DONE` comes after the last bundle.',
      '`spire-agent-image verify <image>` checks these clauses. The labels are declared only, not checked.',
    ] },
    { h: 'Coupling to watch', items: [
      'The file names in `/handoff` are a contract with `spire-publisher` (sheet 06).',
      'The transcript format on stdout is a contract with the harness adapter in the run worker.',
      'The Claude Code flags were measured on CLI 2.1.319. A CLI upgrade must run the canary tests again.',
      'Both images are built from `deploy/agent`, on `node:22-bookworm-slim`.',
    ] },
  ],
};
