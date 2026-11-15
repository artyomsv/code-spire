import { n, e, g } from '../dsl.mjs';

export default {
  slug: 'run-unit',
  title: 'The run unit',
  kicker: 'Five small images work together for one run. Each one holds only what it needs.',
  summary: 'This sheet shows a review run with the credential proxy on (the default for reviews). The agent reads code from an untrusted branch, so it gets no real credential and no route to the internet. The four sheets after this one show each image alone.',
  size: [1440, 850],
  groups: [
    g('unit', 280, 104, 960, 624, 'One run unit: made for one run, destroyed after it', { subject: true }),
  ],
  nodes: [
    n('rw', 'service', 36, 150, 150, 486, 'spire-run-worker', ['makes the unit,', 'follows two logs,', 'destroys the unit']),

    n('agent', 'service', 300, 150, 300, 110, 'Agent', ['spire-agent-codex or spire-agent-claude-code', 'holds a placeholder, not a credential']),
    n('mp', 'service', 730, 150, 190, 110, 'Model proxy', ['spire-model-proxy', 'holds the model key'], { key: true }),
    n('ep', 'service', 1050, 150, 170, 110, 'Egress proxy', ['spire-egress-proxy', 'lets declared', 'hosts through']),
    n('vendor', 'ext', 1290, 150, 120, 110, 'Model vendor', ['OpenAI or', 'Anthropic']),

    n('inp', 'store', 300, 360, 140, 84, '/inputs', ['review files']),
    n('ws', 'store', 460, 360, 140, 84, '/workspace', ['the clone']),
    n('hand', 'store', 730, 360, 190, 84, '/handoff', ['bundles, artifact, DONE']),

    n('init', 'service', 300, 540, 300, 96, 'Init container', ['spire-publisher image, spire-clone', 'runs first, then exits'], { key: true }),
    n('pub', 'service', 730, 540, 190, 96, 'Publisher', ['spire-publisher', 'no credential on a review']),
    n('forge', 'ext', 1290, 540, 120, 96, 'Forge', ['git over', 'HTTPS']),

    n('note', 'note', 960, 344, 0, 0, 'A build run is different', [
      'There is no model proxy.',
      'The agent holds the model key.',
      'The agent reaches the vendor through',
      'the egress proxy, which reports each',
      'host, or refuses it if so configured.',
      'The publisher holds the git credential',
      'and pushes the branch.',
    ]),
  ],
  edges: [
    e('rw', 'agent', 'sync', 'command, env', { ao: -188, dx: -8 }),
    e('rw', 'init', 'sync', 'starts first', { ao: 195, dx: -8 }),
    e('agent', 'mp', 'sync', 'agent network\nspire-model:8080'),
    e('mp', 'ep', 'sync', 'model network\nspire-egress:8888'),
    e('ep', 'vendor', 'sync', '443'),
    e('inp', 'agent', 'file', 'read-only', { as: 't', bs: 'b', bo: -80 }),
    e('agent', 'ws', 'file', '', { as: 'b', bs: 't', ao: 80, both: true }),
    e('agent', 'hand', 'file', 'bundles', { as: 'b', bs: 't', ao: 140, mid: 312, seg: 1 }),
    e('init', 'inp', 'file', 'copies in', { as: 't', bs: 'b', ao: -80 }),
    e('init', 'ws', 'file', 'clone', { as: 't', bs: 'b', ao: 80 }),
    e('hand', 'pub', 'file', 'read-only'),
    e('init', 'forge', 'sync', 'git clone, fetch', { as: 'b', bs: 'b', mid: 684, seg: 1 }),
    e('pub', 'forge', 'sync', 'git push (build runs)'),
  ],
  sections: [
    { h: 'Who holds what', wide: true, head: ['Container', 'Image', 'Mounts', 'Network', 'Credential'], rows: [
      ['Init', '`spire-publisher`, script `spire-clone`', '`/workspace` and `/inputs` read-write', 'default bridge', 'The clone secret. Today this is the same machine-account secret as the write one.'],
      ['Agent', '`spire-agent-codex` or `spire-agent-claude-code`', '`/workspace` and `/handoff` read-write, `/inputs` read-only', 'the agent network only. It is internal and has no route out.', 'A placeholder when the model proxy is on. The model key or seat in a build run.'],
      ['Publisher', '`spire-publisher`', '`/handoff` read-only. It never mounts `/workspace` or `/inputs`.', 'default bridge', 'The git write secret in a build run. None on a review (`spire-publish-none`).'],
      ['Model proxy', '`spire-model-proxy`', 'none', 'the agent network and the model network', 'The real model key, or the access token and account id of a seat.'],
      ['Egress proxy', '`spire-egress-proxy`', 'none', 'default bridge, and the agent network or the model network', 'None.'],
    ] },
    { h: 'Start order', items: [
      'Volumes: `workspace`, `handoff`, and `inputs` for a review.',
      'The init container. For a review, the input files are copied into it first. It clones, then exits. It has 15 minutes.',
      'Networks and proxies, when the egress mode is not `off`.',
      'The publisher, then the agent.',
    ] },
    { h: 'Why it is built this way', items: [
      'The agent runs code and reads text that a stranger may have written. So it gets no git credential, and on a review no model credential.',
      'Work leaves the agent only as git bundles in `/handoff`. The publisher checks each bundle before it pushes.',
      'The model proxy lets through only the model paths that the harness declares. A stolen placeholder is worth nothing.',
      'A fork pull request is refused (`fork_requires_credential_proxy`) when no model proxy can hold the credential.',
    ] },
    { h: 'Settings that change the picture', head: ['Setting', 'Values', 'Effect'], rows: [
      ['`SPIRE_RUN_EGRESS_MODE`', '`off`, `report`, `enforce`', '`report` logs every host. `enforce` refuses a host that the harness did not declare. A proxied unit always enforces.'],
      ['`SPIRE_RUN_CREDENTIAL_PROXY_MODE`', '`OFF`, `REVIEWS`, `ALL`', '`REVIEWS` is the default: review runs get a model proxy.'],
    ] },
    { h: 'Coupling to watch', items: [
      'One machine-account secret fills the clone slot and the push slot today. So the init container also holds a token that can write.',
      'The topology exists for Docker only. There is no Kubernetes form of it.',
      'Codex and Claude Code declare a model route. Any other harness runs without the model proxy.',
    ] },
  ],
};
