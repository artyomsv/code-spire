import { n, e, g } from '../dsl.mjs';

const X = [408, 622, 836], Y = [130, 240, 350, 460], PW = 196, PH = 84;
const tin = (i, id, title, line) => n(id, 'topic', 48, 120 + i * 54, 226, 44, title, [line]);
const tout = (i, id, title, line) => n(id, 'topic', 1166, 120 + i * 54, 226, 44, title, [line]);
const lib = (i, title, line) => n(`l${i}`, 'lib', 52 + (i % 4) * 182, 668 + Math.floor(i / 4) * 68, 170, 54, title, [line]);

export default {
  slug: 'run-worker',
  title: 'spire-run-worker',
  kicker: 'It turns one run command into containers on a Docker host, and reads what they say.',
  summary: 'The run worker places a run unit (sheet 05) for each run command, follows the logs of the agent and the publisher, and reports the result. It runs no git and keeps no files. Its claims and leases are in Postgres, so any replica can clean up after another one.',
  size: [1440, 1010],
  groups: [
    g('rw', 390, 96, 660, 474, 'spire-run-worker', { subject: true }),
    g('inbus', 36, 96, 250, 246, 'Kafka: topics it reads'),
    g('outbus', 1154, 96, 250, 300, 'Kafka: topics it writes'),
    g('libs', 36, 644, 752, 160, 'Libraries it is built from'),
  ],
  nodes: [
    tin(0, 'i1', 'cs.run-commands', 'builds, drafts, verify'),
    tin(1, 'i2', 'cs.review-runs', 'harness reviews'),
    tin(2, 'i3', 'cs.run-control', 'every replica gets each record'),
    tin(3, 'i4', 'cs.harness-*-commands', 'image facts, seat sign-in'),

    tout(0, 'o1', 'cs.run-results', 'waits for the broker ack'),
    tout(1, 'o2', 'cs.run-events', 'transcript, may drop lines'),
    tout(2, 'o3', 'cs.run-verifications', 'verify results'),
    tout(3, 'o4', 'cs.harness-*-results', 'image facts, sign-in'),
    tout(4, 'o5', 'cs.dlq', 'records that failed'),

    n('d1', 'part', X[0], Y[0], PW, PH, 'RunDispatcher', ['the build lane,', 'one after another']),
    n('d2', 'part', X[1], Y[0], PW, PH, 'ReviewRunDispatcher', ['the review lane,', '2 at a time for each replica']),
    n('ctl', 'part', X[2], Y[0], PW, PH, 'RunControlListener', ['cancel, steer,', 'hold, publish']),
    n('exec', 'part', X[0], Y[1], PW, PH, 'RunExecution', ['claims the run, acks,', 'takes the lease']),
    n('launch', 'part', X[1], Y[1], PW, PH, 'RunLauncher', ['RunUnitBuilder makes the spec,', 'Credentials decrypts'], { key: true }),
    n('rt', 'part', X[2], Y[1], PW, PH, 'WorkerRuntimes', ['DockerRunRuntime: create,', 'attach, salvage, destroy']),
    n('lease', 'part', X[0], Y[2], PW, PH, 'Leases and watchdog', ['heartbeat every 30 s,', 'orphan sweep every 5 min']),
    n('har', 'part', X[1], Y[2], PW, PH, 'HarnessRegistry', ['Codex and Claude Code:', 'command line, hosts, parsing']),
    n('read', 'part', X[0], Y[3], PW, PH, 'Log readers', ['agent log to transcript,', 'publisher log to outcome']),
    n('out', 'part', X[1], Y[3], PW, PH, 'LaneResults', ['RunStarted, RunFinished,', 'RunFailed, artifacts']),

    n('pg', 'store', 48, 386, 226, 96, 'Postgres', ['schema runworker:', 'claims, leases, held work']),
    n('docker', 'ext', X[2], 640, PW, 70, 'Docker daemon', ['through the socket']),
    n('unit', 'service', X[2], 770, PW, 84, 'Run unit', ['init, agent, publisher,', 'proxies: sheet 05']),

    lib(0, 'spire-contract', 'run commands, run results'),
    lib(1, 'spire-runtime', 'the RunRuntime port'),
    lib(2, 'spire-runtime-docker', 'containers, networks'),
    lib(3, 'spire-harness', 'the HarnessAdapter port'),
    lib(4, 'spire-harness-codex', 'drives codex exec'),
    lib(5, 'spire-harness-claude-code', 'drives claude -p'),
    lib(6, 'spire-secrets', 'SecretScrub'),
    lib(7, 'spire-encryption', 'Tink'),
  ],
  edges: [
    e('inbus', 'rw', 'async', 'reads', { ao: 0, bo: -114 }),
    e('rw', 'outbus', 'async', 'writes', { ao: -87, bo: 0 }),
    e('d1', 'exec', 'sync'),
    e('d2', 'exec', 'sync', '', { as: 'b', bs: 't', bo: 40, mid: 226 }),
    e('exec', 'launch', 'sync'),
    e('launch', 'rt', 'sync'),
    e('ctl', 'rt', 'sync'),
    e('launch', 'har', 'sync'),
    e('exec', 'lease', 'sync'),
    e('lease', 'pg', 'sync', 'JDBC', { as: 'l', bs: 'r', bo: -42 }),
    e('read', 'out', 'sync'),
    e('rt', 'docker', 'sync', 'docker.sock'),
    e('docker', 'unit', 'sync', 'starts'),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It reads a run command, claims it in Postgres, and acks the record. A second delivery of the same command does nothing.',
      'It builds the run unit: an init container, the agent, the publisher, and the proxies.',
      'It follows two log streams at the same time. The agent log becomes the transcript. The publisher log becomes the outcome.',
      'It reports `RunStarted`, then one final result. Then it destroys the unit.',
      'A watchdog finds units that no live worker owns. It saves what it can and removes them.',
    ] },
    { h: 'What it does not do', items: [
      'It runs no git and has no working copy. An architecture test (`RunWorkerRunsNoGitTest`) checks this.',
      'It does not choose the credential. Each command carries its credentials, encrypted by the orchestrator.',
      'It does not parse a review. It hands the answer back as `RunReviewArtifact`.',
    ] },
    { h: 'Three lanes in', wide: true, head: ['Topic', 'Handler', 'Commands', 'How it is read'], rows: [
      ['`cs.run-commands`', '`RunDispatcher`', '`ExecuteRun`, `ExecuteWorkRun`, `ExecuteWorkDraftRun`, `VerifyWork`', 'In order. It starts at the newest record.'],
      ['`cs.review-runs`', '`ReviewRunDispatcher`', '`ExecuteReviewRun`, `ExecuteUntrustedReviewRun`', 'Its own pool, so a review never waits behind a build. `SPIRE_RUN_REVIEW_CONCURRENCY`, default 2. It starts at the oldest record.'],
      ['`cs.run-control`', '`RunControlListener`', '`CancelRun`, `SteerRun`, `HoldWorkRun`, `PublishWorkRun`', 'Each replica has its own consumer group, so every replica sees every record.'],
    ] },
    { h: 'One run, step by step', wide: true, head: ['Step', 'Class', 'What happens'], rows: [
      ['1. Claim', '`RunClaimStore`', 'Writes the claim, then acks the Kafka record.'],
      ['2. Check', '`RunExecution`', 'Looks for a cancel that arrived first. Takes the workspace lease.'],
      ['3. Build the spec', '`RunLauncher`, `RunUnitBuilder`', 'Decrypts the credentials. Asks the harness adapter for the command line and the allowed hosts.'],
      ['4. Start the unit', '`DockerRunRuntime`', 'Volumes, init container, networks, proxies, publisher, agent. Emits `RunStarted`.'],
      ['5. Follow', 'two virtual threads', 'Agent log through the adapter to `cs.run-events`. Publisher log to `PublisherOutcome`.'],
      ['6. Collect', '`SidecarRecords`', 'Salvages the unit. Reads the egress proxy log and the model proxy log.'],
      ['7. Release', '`UnitRelease`', 'Destroys the unit. If the salvage failed, it keeps the unit for a later try.'],
      ['8. Report', '`LaneResults`', 'Writes the final result to `cs.run-results` and waits for the ack.'],
    ] },
    { h: 'Data it owns', p: [
      'Postgres schema `runworker`, five migrations: `run_claim`, `run_lease`, `work_run`, `work_publication_revocation`, `work_verify`.',
      'Secrets never reach the log. `SecretLogFilter` and `SecretScrub` remove every live secret from log lines.',
    ] },
    { h: 'Coupling to watch', items: [
      'It needs the Docker socket of its host. This is why the Helm chart does not include it: there is no Kubernetes runtime yet.',
      'It pulls a unit image only when the daemon does not have it. A new image version needs a pin or a manual pull.',
      'The publisher, egress proxy and model proxy images are settings of this service (`SPIRE_PUBLISHER_IMAGE`, `SPIRE_RUN_EGRESS_IMAGE`, `SPIRE_RUN_CREDENTIAL_PROXY_IMAGE`).',
      'It must hold the same Tink keyset as the orchestrator to decrypt the credentials in a command.',
    ] },
  ],
};
