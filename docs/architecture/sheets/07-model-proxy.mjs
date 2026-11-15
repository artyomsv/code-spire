import { n, e, g } from '../dsl.mjs';

const X = [348, 538, 728], Y = [130, 250], PW = 170, PH = 80;

export default {
  slug: 'model-proxy',
  title: 'spire-model-proxy',
  kicker: 'It keeps the model credential away from the agent.',
  summary: 'The agent sends plain HTTP to `spire-model:8080` with a placeholder. The model proxy checks the path, puts in the real credential, and forwards the call to one vendor. The answer goes back with the secret masked.',
  size: [1440, 600],
  groups: [
    g('mp', 330, 96, 586, 252, 'spire-model-proxy', { subject: true }),
  ],
  nodes: [
    n('agent', 'service', 40, 130, 190, 200, 'Agent', ['calls spire-model:8080', 'with a placeholder']),

    n('guard', 'part', X[0], Y[0], PW, PH, 'PathGuard', ['declared paths only,', 'GET and POST only']),
    n('inj', 'part', X[1], Y[0], PW, PH, 'Injector', ['removes agent headers,', 'adds the real credential'], { key: true }),
    n('fwd', 'part', X[2], Y[0], PW, PH, 'Forwarder', ['one vendor, HTTP/1.1,', 'no redirects']),
    n('relay', 'part', X[0], Y[1], PW, PH, 'ResponseRelay', ['masks the secret, fixed', 'body on 401 and 403']),
    n('lim', 'part', X[1], Y[1], PW, PH, 'Limits', ['8 calls at a time,', '8 MiB, 60 min']),
    n('log', 'part', X[2], Y[1], PW, PH, 'RequestLine', ['one log line for', 'each request']),

    n('ep', 'service', 1030, 130, 150, 80, 'Egress proxy', ['enforce mode']),
    n('vendor', 'ext', 1260, 130, 140, 80, 'Model vendor', ['one declared host']),
    n('rw', 'service', 1030, 250, 150, 80, 'spire-run-worker', ['reads the log', 'after the run']),

    n('note', 'note', 348, 396, 0, 0, 'No project libraries', ['It is a plain Java program that depends on the JDK only.', 'It runs as user 1002 and mounts nothing from the unit.']),
  ],
  edges: [
    e('agent', 'guard', 'sync', 'HTTP', { ao: -60 }),
    e('guard', 'inj', 'sync'),
    e('inj', 'fwd', 'sync'),
    e('fwd', 'ep', 'sync', 'CONNECT'),
    e('ep', 'vendor', 'sync', 'TLS'),
    e('relay', 'agent', 'sync', 'answer', { bo: 60 }),
    e('log', 'rw', 'sync', 'stdout'),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It holds the real model credential for one run: an API key, or the access token and account id of a subscription seat.',
      'It lets through only the paths that the harness adapter declared, for example `/v1/messages` for Claude Code.',
      'It removes the credential headers that the agent sent, and adds the real ones.',
      'It writes one log line for each request. The run worker reads the log when the run ends.',
    ] },
    { h: 'What it must never hold', items: [
      'The id token or the refresh token of a seat.',
      'Any volume of the unit. It mounts nothing.',
      'A route to any host other than the one vendor.',
    ] },
    { h: 'Rules it enforces', wide: true, head: ['Area', 'Rule'], rows: [
      ['Paths', 'Exact paths, or whole-segment prefixes. It refuses `..`, `.`, `//`, a backslash, `%` and reserved characters.'],
      ['Methods', 'GET and POST only. An absolute-form target gets 403. An upgrade or CONNECT gets 501.'],
      ['Request headers', 'It drops `Authorization`, `x-api-key`, `chatgpt-account-id`, `Cookie` and `Proxy-Authorization`. It forces `Accept-Encoding: identity`.'],
      ['Answers', 'A vendor 401 or 403 body is replaced with a fixed body. Response headers pass an allowlist. `Location` never passes. The secret is replaced with `spire-model-withheld`.'],
      ['Limits', '8 requests at a time (503 above that), 8 MiB for a request body, 10 minutes idle, 60 minutes in total.'],
    ] },
    { h: 'How the run worker reads the result', items: [
      'A 401 on a model path fails the run as `CREDENTIAL_REJECTED`, and the orchestrator marks that credential.',
      'A refused path fails the run as `BLOCKED_EGRESS`.',
      'A 403 does not fail the run.',
    ] },
    { h: 'How it is set up', p: [
      'Environment: `SPIRE_MODEL_UPSTREAM`, `SPIRE_MODEL_INJECTION` (`BEARER`, `X_API_KEY` or `CHATGPT_SEAT`), `SPIRE_MODEL_PATHS_EXACT`, `SPIRE_MODEL_PATHS_PREFIX`, `SPIRE_MODEL_SECRET`, `SPIRE_MODEL_ACCOUNT`.',
      'The Docker runtime starts it (`ModelProxyContainer`) and waits up to 120 s for it to listen. It sits on two internal networks: one to the agent, one to the egress proxy.',
    ] },
    { h: 'Coupling to watch', items: [
      'Each harness adapter owns its route (`CodexModelProxy`, `ClaudeCodeModelProxy`). A new harness with no route runs without this proxy.',
      'The log line format is a contract with `ModelProxyReport` in the run worker.',
    ] },
  ],
};
