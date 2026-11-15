import { n, e, g } from '../dsl.mjs';

const X = [348, 538, 728], Y = [130, 250], PW = 170, PH = 80;

export default {
  slug: 'egress-proxy',
  title: 'spire-egress-proxy',
  kicker: 'The way out for the agent and the model proxy. It records every host, and it can refuse.',
  summary: 'The egress proxy is tinyproxy on Alpine. When it is on, the agent network has no route to the internet, so each outgoing connection of the agent must go through this proxy. It holds no credential. It does not cover the init container or the publisher.',
  size: [1440, 620],
  groups: [
    g('ep', 330, 96, 586, 252, 'spire-egress-proxy', { subject: true }),
  ],
  nodes: [
    n('agent', 'service', 40, 130, 190, 70, 'Agent', ['in a build run']),
    n('mp', 'service', 40, 256, 190, 70, 'Model proxy', ['in a proxied review run']),

    n('acl', 'part', X[0], Y[0], PW, PH, 'Access rule', ['only the subnet of', 'the unit may connect']),
    n('filt', 'part', X[1], Y[0], PW, PH, 'Host filter', ['report: lets all through', 'enforce: default deny']),
    n('conn', 'part', X[2], Y[0], PW, PH, 'Tunnel', ['CONNECT, ports', '443 and 80 only']),
    n('cfg', 'part', X[0], Y[1], PW, PH, 'Start script', ['writes the config from', 'two env vars']),
    n('log', 'part', X[2], Y[1], PW, PH, 'Connect log', ['every attempt,', 'allowed or refused']),

    n('vendor', 'ext', 1030, 130, 170, 80, 'Declared hosts', ['from the harness adapter']),
    n('rw', 'service', 1030, 250, 170, 80, 'spire-run-worker', ['writes the config,', 'reads the log']),

    n('note', 'note', 348, 436, 0, 0, 'A small sandbox', ['No Linux capabilities, a read-only root, 64 MiB of memory, half a CPU, 64 processes.', 'It has no project libraries. The image is built from deploy/egress.']),
  ],
  edges: [
    e('agent', 'acl', 'sync', 'CONNECT', { bo: -5 }),
    e('mp', 'acl', 'sync', '', { bo: 22, mid: 290 }),
    e('acl', 'filt', 'sync'),
    e('filt', 'conn', 'sync'),
    e('conn', 'vendor', 'sync', 'TLS'),
    e('log', 'rw', 'sync', 'stdout'),
    e('rw', 'cfg', 'sync', 'config in the env', { as: 'b', bs: 'b', mid: 392, seg: 1 }),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'When the egress mode is not `off`, it is the only route out for the agent. In a proxied unit it is the only route out for the model proxy.',
      'It writes each connection attempt to its log: host, port, allowed or refused.',
      'In `enforce` mode it refuses every host that is not on the list.',
    ] },
    { h: 'The modes', head: ['Mode', 'Filter', 'When'], rows: [
      ['`report`', 'None. Every host passes and is logged.', 'The default for a build run.'],
      ['`enforce`', '`FilterDefaultDeny Yes`, and one anchored pattern for each declared host.', 'By setting, and always when the unit has a model proxy.'],
      ['`off`', 'No proxy is started, and no internal networks.', 'By setting only.'],
    ] },
    { h: 'Where the host list comes from', p: [
      'Each harness adapter declares its hosts (`HarnessAdapter.egressHosts`): `api.openai.com` for Codex on an API key, `chatgpt.com` for Codex on a seat, `api.anthropic.com` for Claude Code.',
      '`TinyproxyConfig` in `spire-runtime-docker` renders the config. The run worker passes it in two environment variables: `SPIRE_EGRESS_CONF` and `SPIRE_EGRESS_FILTER`.',
    ] },
    { h: 'How the run worker reads the result', p: [
      '`ProxyLogParser` turns the log into `EgressAttempt(host, port, allowed)`, at most 256 for each run. The attempts are part of the run record.',
    ] },
    { h: 'What it does not cover', wide: true, items: [
      'The init container and the publisher. They are on the default bridge and reach the forge directly. Their connections are not in the egress log and are not filtered.',
      'An agent with the egress mode `off`. No proxy and no internal network exist then, and the agent has a direct route out.',
      'So a clean egress log shows what the agent and the model proxy reached. It does not show every connection of the unit.',
    ] },
    { h: 'Coupling to watch', wide: true, items: [
      'It can chain to a corporate proxy, but only an `http://` one. An `https://` upstream is refused. The basic-auth of that upstream can be in its config.',
      'The log format of tinyproxy is a contract with `ProxyLogParser`.',
      'It needs Docker Engine 28 or later, for the isolated gateway mode of the internal network.',
    ] },
  ],
};
