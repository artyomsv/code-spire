const a = (from, to, label, extra = {}) => ({ a: from, b: to, kind: 'async', label, ...extra });
const c = (from, to, label, extra = {}) => ({ a: from, b: to, kind: 'sync', label, ...extra });

export default {
  slug: 'review-flow',
  title: 'How a review travels',
  kicker: 'One pull request event, from the webhook to the comment on the forge.',
  summary: 'Read from top to bottom. An amber arrow is a Kafka message, and its label is the topic and the message. A white arrow is a direct call. The orchestrator decides each next step. The workers do the steps.',
  size: [1440, 0],
  lanes: [
    { id: 'ui', title: 'Dashboard', kind: 'service', sub: 'spire-ui' },
    { id: 'forge', title: 'Forge', kind: 'ext' },
    { id: 'gw', title: 'spire-gateway', kind: 'service' },
    { id: 'or', title: 'spire-orchestrator', kind: 'service' },
    { id: 'rw', title: 'spire-review-worker', kind: 'service' },
    { id: 'run', title: 'spire-run-worker', kind: 'service' },
    { id: 'llm', title: 'Model vendor', kind: 'ext' },
  ],
  steps: [
    { band: 'A pull request event arrives' },
    c('forge', 'gw', 'webhook POST'),
    c('gw', 'gw', 'find the registration, check the signature and the scope'),
    a('gw', 'or', 'cs.repository-integration'),
    c('gw', 'forge', '202, after the broker ack'),
    c('or', 'or', 'ReviewLifecycle decides, then appends to the event store and to cs.events'),

    { band: 'The review on an API provider' },
    a('or', 'rw', 'cs.commands: FetchDiff'),
    c('rw', 'forge', 'read the diff'),
    a('rw', 'or', 'cs.results: DiffFetched'),
    a('or', 'rw', 'cs.commands: GatherContext'),
    a('rw', 'or', 'cs.results: ContextAssembled'),
    a('or', 'rw', 'cs.commands: GenerateReview'),
    c('rw', 'llm', 'the prompt, one paid call'),
    a('rw', 'or', 'cs.results: ReviewGenerated'),

    { band: 'Post the review' },
    c('or', 'or', 'store the findings, decide the verdict'),
    a('or', 'rw', 'cs.commands: PostComments'),
    c('rw', 'forge', 'post the review and the inline comments'),
    a('rw', 'or', 'cs.results: CommentsPosted'),
    c('or', 'ui', 'WebSocket frame: /api/ws/reviews'),

    { band: 'The same review on a harness: these steps take the place of GenerateReview' },
    a('or', 'rw', 'cs.commands: PrepareHarnessReview'),
    a('rw', 'or', 'cs.results: HarnessReviewPrepared'),
    a('or', 'run', 'cs.review-runs: ExecuteReviewRun'),
    c('run', 'run', 'a run unit: the agent reads the code'),
    c('run', 'llm', 'model calls, through the model proxy'),
    a('run', 'or', 'cs.run-results: RunReviewArtifact'),
    a('or', 'rw', 'cs.commands: FinishHarnessReview'),
    a('rw', 'or', 'cs.results: HarnessReviewGenerated'),
  ],
  sections: [
    { h: 'What to notice', items: [
      'The gateway answers the forge before any review work starts. A slow review never makes a webhook time out.',
      'The review worker never chooses the next step. It does one command and reports. The saga in the orchestrator sends the next command.',
      'The diff is read from the forge again at each step that needs it. It is never stored and never put on the bus.',
      'Before the paid call and before each post, the review worker checks that the pull request head did not move.',
    ] },
    { h: 'What can stop a review', items: [
      'A new push supersedes the review. The orchestrator cancels the harness runs of the old round.',
      'A spend cap is reached. The review ends as `refused`, not as failed.',
      'A provider that is named for the repository is switched off. The review is refused. There is no fallback to another provider.',
      'A command fails. The record goes to `cs.dlq`, and the dashboard shows it.',
    ] },
    { h: 'Three kinds of harness run', head: ['Kind', 'When', 'What the agent gets'], rows: [
      ['`REVIEW`', 'A first review, or a re-review with no open findings', '`/inputs/context.md`'],
      ['`RECONCILE`', 'A re-review with open findings', '`/inputs/context.md` and `/inputs/prior-findings.md`'],
      ['`FOLLOWUP`', 'A reply in a thread', '`/inputs/thread.md`'],
    ] },
    { h: 'Replies', p: [
      'A reply to a finding arrives as a webhook too. On an API provider, the orchestrator sends `AnswerFollowUp`, and the review worker makes the call and posts the answer.',
      'On a harness, the worker prepares the thread (`PrepareHarnessReply`), the run worker runs one `FOLLOWUP` run, and the worker posts the answer (`PostHarnessReply`).',
    ] },
    { h: 'Money', wide: true, p: [
      'Each paid call has one slot in the charge ledger of the orchestrator. A harness review is charged once, under the call slot of the review, with the kind of the run. It is never charged as a run. A review on a subscription seat has a cost of 0.',
    ] },
  ],
};
