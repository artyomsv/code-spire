import { n, e, g } from '../dsl.mjs';

const X = [470, 660, 850], Y = [130, 250, 370], PW = 170, PH = 80;

export default {
  slug: 'publisher',
  title: 'spire-publisher',
  kicker: 'The only container in a run that can write to the forge. It checks before it pushes.',
  summary: 'The publisher image does two jobs. As the init container it clones the repository. As a sidecar it reads the git bundles that the agent leaves, checks them against the push gate, and pushes them. It never checks out a working tree.',
  size: [1440, 720],
  groups: [
    g('pub', 452, 96, 586, 372, 'spire-publisher', { subject: true }),
    g('libs', 452, 530, 396, 96, 'Libraries it is built from'),
  ],
  nodes: [
    n('hand', 'store', 150, 124, 190, 92, '/handoff', ['bundles, artifact,', 'DONE marker']),
    n('rw', 'service', 150, 250, 190, 200, 'spire-run-worker', ['starts the container,', 'reads its stdout']),

    n('hf', 'part', X[0], Y[0], PW, PH, 'HandoffFiles', ['regular files only,', 'with size caps']),
    n('gate', 'part', X[1], Y[0], PW, PH, 'PushGate', ['refuses CI files and', 'protected paths']),
    n('cycle', 'part', X[2], Y[0], PW, PH, 'PublishCycle', ['fetch, diff,', 'gate, push'], { key: true }),
    n('scripts', 'part', X[0], Y[1], PW, PH, 'Six entry scripts', ['spire-clone,', 'spire-publish, ...']),
    n('cfg', 'part', X[1], Y[1], PW, PH, 'PublisherConfig', ['checks the env and', 'the branch rules']),
    n('pol', 'part', X[2], Y[1], PW, PH, 'PublicationPolicy', ['automatic, held,', 'permitted, read-only']),
    n('out', 'part', X[0], Y[2], PW, PH, 'OutcomeWriter', ['one JSON line per event,', 'secret scrubbed']),

    n('forge', 'ext', 1180, 124, 200, 92, 'Forge', ['clone, fetch, push']),

    n('l1', 'lib', 468, 552, 176, 54, 'spire-workspace', ['JGit: clone, gate, bundles']),
    n('l2', 'lib', 656, 552, 176, 54, 'spire-secrets', ['SecretScrub']),
  ],
  edges: [
    e('hand', 'hf', 'file', 'read-only'),
    e('hf', 'gate', 'sync'),
    e('gate', 'cycle', 'sync'),
    e('cycle', 'forge', 'sync', 'git over HTTPS'),
    e('rw', 'scripts', 'sync', 'script, env', { ao: -60 }),
    e('scripts', 'cfg', 'sync'),
    e('cfg', 'pol', 'sync'),
    e('pol', 'cycle', 'sync', 'mode'),
    e('out', 'rw', 'sync', 'stdout', { bo: 60 }),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It clones the repository into `/workspace` for the agent (`spire-clone`).',
      'It waits for bundles in `/handoff`, until the agent writes `DONE`.',
      'It runs the push gate before every push.',
      'It tells the run worker what happened, one JSON line for each event: `pushed`, `checkpoint`, `gate_refused`, `artifact`, `failed`.',
    ] },
    { h: 'What it must never do', items: [
      'Mount `/workspace` or `/inputs` as the publisher. It sees only `/handoff`, read-only.',
      'Check out a working tree. It works on git objects only.',
      'Print its credential. `OutcomeWriter` scrubs every line.',
    ] },
    { h: 'The six entry scripts', wide: true, head: ['Script', 'Main class', 'Used for', 'Credential'], rows: [
      ['`spire-clone`', '`CloneMain`', 'The init container. Clones and fetches the base.', 'clone secret'],
      ['`spire-publish`', '`PublisherMain`', 'A standalone fix run: push each checked bundle.', 'git write secret'],
      ['`spire-publish-held`', '`HeldPublisherMain`', 'A work-item build: keep a checkpoint, push nothing.', 'git write secret'],
      ['`spire-publish-permitted`', '`PermittedPublisherMain`', 'Deliver a held build: push one exact commit, inside a time window.', 'git write secret'],
      ['`spire-publish-none`', '`ReadOnlyPublisherMain`', 'A review or a draft run: pass the artifact, push nothing.', 'none'],
      ['`spire-verify-prepare`', '`VerifyPrepareMain`', 'Prepare a clean copy of a checkpoint for the verify step.', 'clone secret'],
    ] },
    { h: 'Rules of the push gate', items: [
      'It refuses a change to CI files: `.github/workflows/**`, `.gitlab-ci.yml`, `Jenkinsfile`, `.tekton/**`, `action.yml` and similar. These come from `ProtectedPaths.CI_FLOOR`.',
      'It refuses a symlink at or above those paths, and any path that the repository profile protects.',
      'The branch must be under `spire/` and must not be the base branch. Trunk names are always refused.',
      'In permitted mode it pushes only the permitted commit, and it refuses if the remote head moved.',
      'In held mode a checkpoint must descend from the start commit.',
    ] },
    { h: 'How it talks', p: [
      'In: environment variables (`SPIRE_REMOTE_URI`, `SPIRE_BRANCH`, `SPIRE_BASE_COMMIT`, `SPIRE_PROTECTED_PATHS`, the permit values) and files in `/handoff`. Nothing calls it over the network.',
      'Out: stdout, and git over HTTPS to the forge. Exit code 0 is success, 1 is a failure, 2 is a wrong configuration.',
    ] },
    { h: 'Coupling to watch', items: [
      'It meets the agent only through files. The agent image contract (sheet 09) is the other half of this file protocol.',
      '`spire-workspace` is also a dependency of the orchestrator, which uses its `ProtectedPaths`.',
      'It is a plain Java program on `eclipse-temurin:25-jre-alpine`, user 1001. It does not use Quarkus or Kafka.',
    ] },
  ],
};
