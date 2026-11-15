import { n, e, g } from '../dsl.mjs';

const WX = [370, 518, 666, 814, 962], WW = 136;
const lib = (i, title, line) => n(`l${i}`, 'lib', 52 + i * 162, 828, 152, 54, title, [line]);

export default {
  slug: 'review-worker',
  title: 'spire-review-worker',
  kicker: 'The hands of the reviewer. It does every step that touches the outside world.',
  summary: 'The review worker takes one command at a time from `cs.commands`. It reads the diff, gathers context, makes the paid LLM call, and posts the comments. It reports each outcome on `cs.results`. It decides nothing about what comes next.',
  size: [1440, 1030],
  groups: [
    g('rw', 350, 96, 768, 500, 'spire-review-worker', { subject: true }),
    g('libs', 36, 804, 1160, 96, 'Libraries it is built from'),
  ],
  nodes: [
    n('cmd', 'topic', 48, 130, 200, 52, 'cs.commands', ['from the orchestrator']),
    n('ui', 'service', 48, 478, 200, 70, 'spire-ui', ['the context card']),
    n('res', 'topic', 1200, 394, 200, 52, 'cs.results', ['to the orchestrator']),
    n('dlq', 'topic', 1200, 130, 200, 52, 'cs.dlq', ['records that failed']),

    n('disp', 'part', 370, 128, 728, 56, 'CommandDispatcher', ['one command at a time, in order']),
    n('w1', 'part', WX[0], 232, WW, 110, 'DiffWorker', ['FetchDiff:', 'reads the diff,', 'stores nothing']),
    n('w2', 'part', WX[1], 232, WW, 110, 'ContextWorker', ['GatherContext:', 'asks every provider,', 'stores the result']),
    n('w3', 'part', WX[2], 232, WW, 110, 'ReviewWorker', ['GenerateReview,', 'PostComments']),
    n('w4', 'part', WX[3], 232, WW, 110, 'Harness workers', ['prepare the inputs,', 'finish the answer,', 'post the reply']),
    n('w5', 'part', WX[4], 232, WW, 110, 'FollowUpWorker', ['answers a reply,', 'confirms or refuses', 'a finding']),
    n('emit', 'part', 370, 392, 728, 56, 'ResultsEmitter', ['waits up to 10 s for the broker ack']),
    n('cli', 'part', 370, 492, 350, 78, 'Clients for this command', ['built from the encrypted credential', 'that the command carries'], { key: true }),
    n('claim', 'part', 748, 492, 350, 78, 'CommentIdempotencyStore', ['a claim before every post', 'and before every paid call']),

    n('forge', 'ext', 370, 680, 170, 78, 'Forges', ['diff, threads,', 'comments, reviews']),
    n('llm', 'ext', 556, 680, 170, 78, 'LLM vendors', ['OpenAI-compatible,', 'Anthropic, Gemini']),
    n('ctx', 'ext', 742, 680, 170, 78, 'Context sources', ['Jira, Confluence,', 'issues, source files']),
    n('pg', 'store', 928, 672, 170, 94, 'Postgres', ['schema worker']),

    lib(0, 'spire-contract', 'commands, events, ports'),
    lib(1, 'spire-scm-*', 'forge adapters'),
    lib(2, 'spire-llm', 'client, prompts, parsers'),
    lib(3, 'spire-diff', 'diff parsing, anchors'),
    lib(4, 'spire-context-*', 'tickets and pages'),
    lib(5, 'spire-context-code', 'symbols the diff touches'),
    lib(6, 'spire-encryption', 'Tink'),
  ],
  edges: [
    e('cmd', 'disp', 'async', 'commands', { dx: -10 }),
    e('disp', 'w1', 'sync'), e('disp', 'w2', 'sync'), e('disp', 'w3', 'sync'), e('disp', 'w4', 'sync'), e('disp', 'w5', 'sync'),
    e('w1', 'emit', 'sync'), e('w2', 'emit', 'sync'), e('w3', 'emit', 'sync'), e('w4', 'emit', 'sync'), e('w5', 'emit', 'sync'),
    e('emit', 'res', 'async', 'results'),
    e('rw', 'dlq', 'async', 'failures', { as: 'r', bs: 'l', ao: -190 }),
    e('ui', 'rw', 'sync', '/wk', { bo: 167 }),
    e('rw', 'forge', 'sync', 'HTTPS', { ao: -279 }),
    e('rw', 'llm', 'sync', 'prompt', { ao: -93 }),
    e('rw', 'ctx', 'sync', 'HTTPS', { ao: 93 }),
    e('rw', 'pg', 'sync', 'JDBC', { ao: 279 }),
  ],
  sections: [
    { h: 'What it is responsible for', items: [
      'It reads the diff from the forge. The diff is never stored. Each later step reads it again by commit.',
      'It gathers context: linked tickets and pages, repository rules, and the code that the diff touches.',
      'It makes the paid LLM call for a review, for a re-review (reconcile), and for a reply.',
      'It posts the review, the inline comments and the replies, and it resolves threads.',
      'For a harness review it prepares the input files and later parses the answer. The run worker runs the model.',
    ] },
    { h: 'Commands in, events out', head: ['Command', 'Handler', 'Events it emits'], rows: [
      ['`FetchDiff`', '`DiffWorker`', '`DiffFetched` (numbers only)'],
      ['`GatherContext`', '`ContextWorker`', '`ContextRequested`, `ContextContributed`, `ContextAssembled`'],
      ['`GenerateReview`', '`ReviewWorker`', '`ReviewGenerated`'],
      ['`PostComments`', '`ReviewWorker`', '`CommentsPosted`'],
      ['`PrepareHarnessReview`, `FinishHarnessReview`', '`HarnessReviewWorker`', '`HarnessReviewPrepared`, `HarnessReviewGenerated`'],
      ['`PrepareHarnessReply`, `PostHarnessReply`', '`HarnessReplyWorker`', '`HarnessReplyPrepared`, `FollowUpPosted`'],
      ['`AnswerFollowUp`, `ConfirmFinding`, `RefuseFinding`', '`FollowUpWorker`', '`FollowUpGenerated`, `FollowUpPosted`, `FindingConfirmed`, `FindingRefused`'],
    ] },
    { h: 'One API review, step by step', wide: true, head: ['Step', 'Class', 'What happens'], rows: [
      ['1. Fetch the diff', '`DiffWorker`', 'Reads the diff through `DiffSource` with retries. Emits only metadata.'],
      ['2. Gather context', '`ContextWorker`', 'Asks all providers in two levels, 20 s for each level. Encrypts the result and stores it in `context_blob`.'],
      ['3. Start the review', '`ReviewWorker.generateReview`', 'Checks that the pull request head did not move. Reads the diff again. Loads the context.'],
      ['4. Reconcile', '`ReconcileSteps`', 'Only when an earlier review had findings. A second paid call decides which old findings are fixed.'],
      ['5. Call the model', '`WorkerLlmProvider`', 'Builds the prompt with `ReviewPromptBuilder`. The call has a circuit breaker and a claim, so a redelivery does not pay twice.'],
      ['6. Parse', '`FindingsParser`', 'Turns the answer into findings. Saves the result before it emits `ReviewGenerated`.'],
      ['7. Post', '`ReviewWorker.postComments`', 'Checks the head again. Posts a forge review (GitHub) or inline comments. Every post has a claim.'],
      ['8. Escape', '`MarkdownHtmlEscape`', 'Escapes raw HTML in each finished comment, outside Markdown code.'],
    ] },
    { h: 'Data it owns', p: [
      'Postgres schema `worker`, five migrations. `comment_idempotency` holds the claims. `context_blob` holds the encrypted context. `code_symbol` is the symbol index, pruned every 24 hours.',
      'There is no object store. The `BlobStore` port is backed by Postgres.',
    ] },
    { h: 'Limits that shape it', items: [
      'It takes one record at a time (`max.poll.records=1`).',
      'One command can take two LLM calls (180 s each) and a 180 s backoff while it posts. `LlmTimeoutBudget` refuses to start the service if the Kafka ack time is shorter than that.',
      'A record that fails goes to `cs.dlq`. A harness answer that fails in a transient way is kept there for a replay, because the model call is already paid.',
    ] },
    { h: 'Coupling to watch', wide: true, items: [
      'The orchestrator deletes rows from this schema directly (claims and context blobs), on a re-run and on an unarchive.',
      'Each command carries the credentials it needs, encrypted by the orchestrator. Both services must hold the same Tink keyset.',
      'Context providers fail soft. A provider that finds nothing looks the same as a pull request with no context.',
      'The dashboard calls this service directly for one thing: `GET /wk/review-context/...`. It has its own OIDC client and its own session cookie.',
    ] },
  ],
};
