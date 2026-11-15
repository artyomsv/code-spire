const a = (from, to, label, extra = {}) => ({ a: from, b: to, kind: 'async', label, ...extra });
const c = (from, to, label, extra = {}) => ({ a: from, b: to, kind: 'sync', label, ...extra });

export default {
  slug: 'work-item-flow',
  title: 'How a work item is built',
  kicker: 'The factory: from a labelled ticket to a merged pull request.',
  summary: 'Read from top to bottom. A person writes a ticket, labels it, and approves. The system does the other steps. This sheet shows the main path on GitHub, with a generated specification and plan.',
  size: [1440, 0],
  lanes: [
    { id: 'op', title: 'Operator', kind: 'ext', sub: 'in the dashboard' },
    { id: 'forge', title: 'Tracker and forge', kind: 'ext' },
    { id: 'gw', title: 'spire-gateway', kind: 'service' },
    { id: 'or', title: 'spire-orchestrator', kind: 'service' },
    { id: 'run', title: 'spire-run-worker', kind: 'service' },
    { id: 'unit', title: 'Run unit', kind: 'service', sub: 'agent and publisher' },
  ],
  steps: [
    { band: 'Intake' },
    c('forge', 'gw', 'webhook: a ticket got the label'),
    a('gw', 'or', 'cs.work-integration'),
    c('or', 'forge', 'WorkSourceScanner also reads candidates, every 30 s'),
    c('or', 'or', 'admit the item, if the policy of the repository allows it'),

    { band: 'Specification and plan' },
    a('or', 'run', 'cs.run-commands: ExecuteWorkDraftRun'),
    c('run', 'unit', 'a read-only unit: no write credential'),
    a('run', 'or', 'cs.run-results: RunWorkArtifact'),
    c('or', 'forge', 'post the draft on the ticket'),
    c('op', 'or', 'approve the plan, or ask for a revision'),

    { band: 'Build and verify, once for each step of the plan' },
    a('or', 'run', 'cs.run-commands: ExecuteWorkRun'),
    c('run', 'unit', 'a unit with a held publisher'),
    c('unit', 'unit', 'the agent commits, a bundle goes to /handoff, the publisher keeps a checkpoint'),
    a('run', 'or', 'cs.run-results: RunWorkReady'),
    a('or', 'run', 'cs.run-commands: VerifyWork'),
    c('run', 'run', 'run the check commands on a clean copy, with no credentials'),
    a('run', 'or', 'cs.run-verifications: passed, failed or unverified'),

    { band: 'Deliver' },
    a('or', 'run', 'cs.run-control: PublishWorkRun, with an exact permit'),
    c('unit', 'forge', 'the publisher pushes the branch'),
    a('run', 'or', 'cs.run-results: RunFinished'),
    c('or', 'forge', 'open the pull request, request the reviewer'),
    c('forge', 'gw', 'webhook: pull request opened. The review flow starts (sheet 13)'),

    { band: 'Land' },
    c('op', 'or', 'approve the land gate'),
    c('or', 'forge', 'merge at the approved head, delete the branch, comment on the ticket'),
  ],
  sections: [
    { h: 'What to notice', items: [
      'The orchestrator owns the state of the item. The run worker and the unit only do one run and report.',
      'The build does not push. The publisher holds a checkpoint, and the workspace is kept. A push happens only after a permit for one exact commit.',
      'The pull request that the factory opens is reviewed by the same reviewer as any other pull request. The factory does not review its own work.',
      'The steps of a plan build on one chain of checkpoints. All steps together become one pull request.',
    ] },
    { h: 'Where a person decides', items: [
      'The label on the ticket: this is the request.',
      'The plan gate: approve, or revise. An autonomous repository can skip this gate.',
      'A verify gate, only when verify failed or could not run: retry the build, or stop.',
      'The land gate: approve the merge.',
      'Take over: an operator can stop the factory on an item at any time. Publication is revoked before the compute stops.',
    ] },
    { h: 'How the steps are driven', p: [
      'Most arrows that leave the orchestrator on this sheet start from a timer, not from a message. A timer reads the work-item tables every 5 seconds and moves each row to its next state. Effects on the tracker (comments, labels) go through an outbox table, so they survive a restart.',
    ] },
    { h: 'What can go another way', items: [
      'A repository with the `composed` setup has no draft run. The system composes the specification and the plan from the ticket.',
      'A verify that fails opens a gate. Retry build starts again from the checkpoint.',
      'A merge that the forge refuses for a code reason starts a land fix run, then a new land gate. Any other refusal goes to the operator.',
      'A person can merge or close the pull request by hand. The factory sees it through the webhook, or through a read every 2 minutes.',
    ] },
    { h: 'Limits today', wide: true, items: [
      'Landing works on GitHub only. GitLab and Bitbucket cannot land, and cannot mark a draft pull request as ready.',
      'The generated specification, plan and steps were proven live on one harness and one model.',
      'A work item needs the run worker, so it needs a Docker host. The Helm chart installs the reviewer only.',
    ] },
  ],
};
