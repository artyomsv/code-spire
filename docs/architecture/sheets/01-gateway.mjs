import { n, e, g } from '../dsl.mjs';

const X = [555, 735, 915], Y = [128, 250, 372, 494], PW = 160, PH = 78;

export default {
  slug: 'gateway',
  title: 'spire-gateway',
  kicker: 'The front door. It checks each webhook and puts it on the bus.',
  summary: 'The gateway is the only part that answers a forge in real time. It owns the webhook registrations, checks each signature, turns the payload into a contract event, and answers 202 only after Kafka has the message.',
  size: [1440, 980],
  groups: [
    g('gw', 540, 96, 550, 500, 'spire-gateway', { subject: true }),
    g('bus', 1130, 96, 280, 232, 'Kafka'),
    g('libs', 540, 770, 760, 96, 'Libraries it is built from'),
  ],
  nodes: [
    n('forges', 'ext', 40, 120, 170, 80, 'Forges', ['GitHub, GitLab,', 'Bitbucket Cloud']),
    n('browser', 'ext', 40, 420, 170, 70, 'Operator browser', ['the dashboard']),
    n('ui', 'service', 320, 120, 130, 440, 'spire-ui', ['its nginx is the', 'only way in']),

    n('res', 'part', X[0], Y[0], PW, PH, 'Webhook endpoints', ['/webhooks/{forge}/{key}']),
    n('edge', 'part', X[1], Y[0], PW, PH, 'RegistryWebhookEdge', ['find key, check signature,', 'translate, check scope']),
    n('pub', 'part', X[2], Y[0], PW, PH, 'Publishers', ['wait for the broker ack,', 'then answer 202']),
    n('api', 'part', X[0], Y[1], PW, PH, 'Registrations API', ['/gw/webhook-repos', 'admin only']),
    n('reg', 'part', X[2], Y[1], PW, PH, 'WebhookRepoRegistry', ['JDBC, webhook secrets', 'encrypted with Tink'], { key: true }),
    n('relay', 'part', X[1], Y[2], PW, PH, 'Snapshot relay', ['reads the outbox', 'every 5 s']),
    n('auth', 'part', X[0], Y[3], PW, PH, 'Sign-in', ['/gw/auth', 'its own OIDC client']),
    n('att', 'part', X[0], Y[2], PW, PH, 'Attention feed', ['/gw/ws/webhook-attention']),

    n('t1', 'topic', 1145, 120, 250, 52, 'cs.repository-integration', ['RepositoryDelivery']),
    n('t2', 'topic', 1145, 188, 250, 52, 'cs.work-integration', ['WorkSourceDelivery']),
    n('t3', 'topic', 1145, 256, 250, 52, 'cs.registry-integration', ['RepositoryRegistration']),
    n('orch', 'service', 1145, 400, 250, 70, 'spire-orchestrator', ['reads all three topics']),

    n('idp', 'ext', X[0], 650, PW, 70, 'Identity provider', ['OIDC (Keycloak)']),
    n('pg', 'store', X[2], 640, PW, 90, 'Postgres', ['schema gateway:', 'webhook_repo, outbox']),

    n('l1', 'lib', 555, 790, 170, 58, 'spire-scm-*', ['signature check, translation']),
    n('l2', 'lib', 740, 790, 170, 58, 'spire-worksource-*', ['issue webhooks']),
    n('l3', 'lib', 925, 790, 170, 58, 'spire-encryption', ['the webhook keyset']),
    n('l4', 'lib', 1110, 790, 170, 58, 'spire-contract', ['events, envelopes, keys']),
  ],
  edges: [
    e('forges', 'ui', 'sync', 'webhook POST', { bo: -180 }),
    e('browser', 'ui', 'sync', 'HTTPS', { bo: 115 }),
    e('ui', 'res', 'sync', '/webhooks', { ao: -173 }),
    e('ui', 'api', 'sync', '/gw', { ao: -51 }),
    e('ui', 'auth', 'sync', '/gw', { ao: 193 }),
    e('ui', 'att', 'sync', '/gw', { ao: 71 }),
    e('res', 'edge', 'sync'),
    e('edge', 'pub', 'sync'),
    e('edge', 'reg', 'sync', 'find by key', { as: 'b', bs: 'l', bo: -16, seg: 0, at: 0.45 }),
    e('api', 'reg', 'sync', 'create, rotate, delete', { ao: 16, bo: 16, at: 0.3 }),
    e('reg', 'pg', 'sync', 'JDBC'),
    e('pg', 'relay', 'sync', 'outbox rows', { as: 'l', bs: 'b', seg: 0 }),
    e('auth', 'idp', 'sync', 'OIDC'),
    e('pub', 't1', 'async', '', { ao: -12, mid: 1112 }),
    e('pub', 't2', 'async', '', { ao: 12, mid: 1102 }),
    e('relay', 't3', 'async', '', { mid: 1121 }),
    e('bus', 'orch', 'async', 'consumed by'),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It receives webhooks from GitHub, GitLab and Bitbucket Cloud at `/webhooks/{forge}/{key}`.',
      'The `{key}` selects one registration row. A row has one kind: REVIEWER (pull request events), FACTORY (repository activity) or ISSUE (tracker tickets).',
      'It checks the signature with the secret of that row, then checks that the payload is for the registered repository.',
      'It publishes the event and waits for the broker. A failed publish answers 500, so the forge sends the webhook again.',
      'It serves the admin API for registrations and a live feed of rejected deliveries.',
    ] },
    { h: 'What it does not do', items: [
      'It does not consume any Kafka topic.',
      'It does not call a forge or any other outside system.',
      'It does not remove duplicate deliveries. It sends a SHA-256 of the body as `deliveryId`, and the consumer removes duplicates.',
      'It has no rate limit. The only limit is a 1 MB body.',
    ] },
    { h: 'Messages out', head: ['Topic', 'Carries', 'Key', 'Sent by'], rows: [
      ['`cs.repository-integration`', '`RepositoryDelivery`, a verified forge event', 'review id', '`IntegrationPublisher`'],
      ['`cs.work-integration`', '`WorkSourceDelivery`, a tracker ticket event', 'work item id', '`WorkIngressPublisher`'],
      ['`cs.registry-integration`', '`RepositoryRegistration`, a snapshot with no secret', 'registration id', '`RepositorySnapshotPublisher`'],
    ] },
    { h: 'Data it owns', p: [
      'Postgres schema `gateway`, with its own database role and its own Flyway migrations. The tables are `webhook_repo` and `repository_snapshot_outbox`. Database triggers fill the outbox.',
      'The gateway shares the Postgres instance with the other services, but it cannot read their schemas. The orchestrator learns about registrations only from `cs.registry-integration`.',
    ] },
    { h: 'Coupling to watch', wide: true, items: [
      'The orchestrator also writes to `cs.repository-integration` (manual registration of a pull request). The topic has two producers.',
      'Sessions are not shared. The gateway cookie is scoped to `/gw`. The dashboard signs in to `/api` first and then chains to `/gw` and `/wk`.',
      'Bitbucket Cloud has no ISSUE path: there is no Bitbucket work source.',
      'Some code comments and `README.md` still name the old topic `cs.integration`. The gateway does not publish to it.',
    ] },
  ],
};
