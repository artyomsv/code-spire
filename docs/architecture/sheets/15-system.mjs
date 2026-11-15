import { n, e } from '../dsl.mjs';

export default {
  slug: 'system',
  title: 'The whole system',
  kicker: 'Four services around one message bus, one way in, and a disposable box for each agent run.',
  summary: 'This is the high-level picture. Each box has its own sheet. The services never call each other: every amber arrow is a message through Kafka.',
  size: [1440, 1000],
  groups: [],
  nodes: [
    n('browser', 'ext', 40, 130, 180, 70, 'Operator browser', ['the dashboard']),
    n('idp', 'ext', 40, 330, 180, 70, 'Identity provider', ['OIDC, one client', 'for each service']),
    n('forges', 'ext', 40, 530, 180, 80, 'Forges and trackers', ['GitHub, GitLab,', 'Bitbucket Cloud, Jira']),

    n('ui', 'service', 310, 130, 140, 480, 'spire-ui', ['its nginx is the', 'only way in', '', 'sheet 10']),

    n('gw', 'service', 560, 130, 230, 100, 'spire-gateway', ['checks webhooks,', 'owns the registrations', 'sheet 01']),
    n('or', 'service', 940, 130, 260, 100, 'spire-orchestrator', ['decides each next step,', 'serves the dashboard', 'sheet 02']),
    n('bus', 'topic', 560, 350, 640, 56, 'Kafka', ['21 topics: every message between services goes through here (sheet 12)']),
    n('run', 'service', 560, 520, 230, 100, 'spire-run-worker', ['places a run unit for', 'each run command', 'sheet 04']),
    n('rw', 'service', 940, 520, 260, 100, 'spire-review-worker', ['diff, context, LLM call,', 'posts to the forge', 'sheet 03']),

    n('unit', 'service', 560, 720, 230, 100, 'Run unit', ['agent, publisher, model proxy,', 'egress proxy, on a Docker host', 'sheets 05 to 09']),
    n('pg', 'store', 940, 716, 260, 108, 'Postgres', ['one instance, one schema for each service:', 'gateway, orchestrator, worker, runworker']),

    n('fapi', 'ext', 1270, 130, 140, 100, 'Forge APIs', ['pull requests,', 'merge, labels,', 'comments']),
    n('llm', 'ext', 1270, 565, 140, 100, 'Model vendors', ['OpenAI,', 'Anthropic,', 'Gemini']),
  ],
  edges: [
    e('browser', 'ui', 'sync', 'HTTPS', { bo: -205 }),
    e('browser', 'idp', 'sync', 'sign-in'),
    e('forges', 'ui', 'sync', 'webhooks', { bo: 200 }),
    e('ui', 'gw', 'sync', '/webhooks, /gw', { ao: -190 }),
    e('ui', 'or', 'sync', '/api', { as: 'r', bs: 'b', ao: -110, bo: -90, seg: 0, at: 0.12 }),
    e('ui', 'rw', 'sync', '/wk', { as: 'r', bs: 't', ao: 60, bo: -90, seg: 0, at: 0.12 }),

    e('gw', 'bus', 'async', '3 topics', { bo: -205 }),
    e('or', 'bus', 'async', 'commands out, results in', { bo: 190, both: true }),
    e('bus', 'run', 'async', 'run commands in, results out', { ao: -205, both: true }),
    e('bus', 'rw', 'async', 'commands in, results out', { ao: 190, both: true }),

    e('run', 'unit', 'sync', 'Docker socket'),
    e('unit', 'forges', 'sync', 'git clone and push', { as: 'l', bs: 'b', seg: 0 }),
    e('or', 'fapi', 'sync'),
    e('rw', 'fapi', 'sync', '', { as: 'r', bs: 'b', ao: -35, bo: 35 }),
    e('rw', 'llm', 'sync', '', { ao: 30, bo: -15 }),
    e('unit', 'llm', 'sync', 'model calls', { as: 'b', bs: 'b', mid: 858, seg: 1 }),
  ],
  sections: [
    { h: 'The parts in one line each', wide: true, head: ['Part', 'Sheet', 'What it is responsible for'], rows: [
      ['`spire-ui`', '10', 'The dashboard, and the nginx that sends each URL prefix to its service.'],
      ['`spire-gateway`', '01', 'Receives webhooks, checks them against a registration, puts them on the bus.'],
      ['`spire-orchestrator`', '02', 'The core. Runs the review lifecycle and the factory pipeline, keeps settings and read models, serves the dashboard.'],
      ['`spire-review-worker`', '03', 'Does each review step that touches the outside world: diff, context, LLM call, comments.'],
      ['`spire-run-worker`', '04', 'Turns a run command into containers on a Docker host and reports the result.'],
      ['Run unit', '05', 'One set of containers for one agent run. Made from the five images below.'],
      ['`spire-publisher`', '06', 'Clones the repository, and is the only container that can push.'],
      ['`spire-model-proxy`', '07', 'Holds the model credential, so the agent does not.'],
      ['`spire-egress-proxy`', '08', 'The only way out of a run unit. Logs or refuses hosts.'],
      ['`spire-agent-codex`, `spire-agent-claude-code`', '09', 'Run a coding CLI over the code, with no way to push.'],
    ] },
    { h: 'How the parts are coupled', items: [
      'Service to service: only Kafka. There is no HTTP call between two services.',
      'Dashboard to service: HTTP and WebSocket through nginx, by URL prefix (`/api`, `/gw`, `/wk`).',
      'Service to database: each service has its own schema and its own migrations in one Postgres instance.',
      'Run worker to run unit: the Docker socket, environment variables, log streams and volumes. No network call.',
      'Agent to publisher: files in the `/handoff` volume only.',
      'Shared secrets: the orchestrator, the review worker and the run worker hold the same Tink keyset, because a command carries encrypted credentials. The gateway has its own keyset for webhook secrets.',
      'Shared code: every service is built on `spire-contract` (sheet 11).',
    ] },
    { h: 'Where the coupling is stronger than the design says', items: [
      'The orchestrator deletes rows in the schema of the review worker. This is the one direct database call between two services.',
      'The factory pipeline in the orchestrator is driven by timers that poll its tables, not by messages.',
      'The orchestrator is large: about 330 source files in 30 packages, and 115 migrations. The reviewer and the factory live in the same service and the same schema.',
      'The orchestrator and the review worker both need almost all forge and context adapters.',
      'Four Kafka topics have a writer but no reader, or a reader but no writer (sheet 12).',
    ] },
    { h: 'Two ways to deploy', head: ['', 'Docker Compose', 'Kubernetes (Helm)'], rows: [
      ['Services', 'ui, gateway, orchestrator, review worker, and the run worker behind the `factory` profile', 'ui, gateway, orchestrator, review worker'],
      ['Run worker', 'Yes. It mounts the Docker socket of the host.', 'No. It needs a Docker host outside the cluster.'],
      ['Postgres, Kafka', 'Containers in the stack', 'Bundled StatefulSets, or your own'],
      ['Identity provider', 'A Keycloak container', 'Your own by default'],
      ['TLS', 'An optional Caddy overlay', 'The Ingress'],
      ['Way in', 'The ui container, port 34700', 'One Ingress rule to the ui Service'],
    ] },
    { h: 'Ports on a developer machine', head: ['Part', 'Port'], rows: [
      ['Dashboard (Vite)', '34000'],
      ['Orchestrator', '34080'],
      ['Gateway', '34081'],
      ['Review worker', '34082'],
      ['Run worker', '34083'],
      ['Postgres, Redpanda', '34432, 34092'],
    ] },
    { h: 'The ten images', wide: true, p: [
      'All ten are public packages on GHCR, under `ghcr.io/artyomsv/`. One root `Dockerfile` builds the four services (`SERVICE=gateway`, `orchestrator`, `review-worker`, `run-worker`). `spire-ui`, `spire-publisher`, `spire-model-proxy`, `spire-egress-proxy` and the two agent images each have their own Dockerfile. `docker.yml` builds `:edge` on each push to master. `release.yml` builds a signed version on a `v*` tag.',
    ] },
  ],
};
