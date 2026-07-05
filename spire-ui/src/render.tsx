import type {
  Finding,
  ReviewDetail,
  ReviewEvent,
  ReviewStatus,
  Usage,
} from './api';

export const STAGES = ['Received', 'Diff', 'Context', 'Review', 'Comments', 'Done'];

export const STATUS_LABEL: Record<ReviewStatus, string> = {
  reviewing: 'Reviewing',
  completed: 'Completed',
  failed: 'Needs attention',
  cancelled: 'Cancelled',
  superseded: 'Superseded',
  observed: 'Observed',
};

export function ago(updatedAt: string): string {
  const s = (Date.now() - Date.parse(updatedAt)) / 1000;
  if (s < 60) return Math.round(s) + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return Math.round(s / 86400) + 'd ago';
}

export function pill(status: ReviewStatus) {
  return (
    <span className={`pill ${status}`}>
      <span className="glyph"></span>
      {STATUS_LABEL[status]}
    </span>
  );
}

// In the list, ReviewSummary carries only `status` + `stage` (no per-segment
// array), so segment states are derived exactly as the mockup's sample data
// would have produced them.
export function miniPipeline(status: ReviewStatus, stage: number) {
  if (status === 'reviewing') {
    const segs = Array.from({ length: 5 }, (_, i) => {
      const cls = i < stage ? 'done' : i === stage ? 'active' : '';
      return <span key={i} className={`seg ${cls}`}></span>;
    });
    return (
      <div className="mini">
        {segs}
        <span className="lbl">{STAGES[stage]}</span>
      </div>
    );
  }
  if (status === 'failed') {
    const segs = Array.from({ length: 5 }, (_, i) => {
      const cls = i < stage ? 'done' : i === stage ? 'failed' : '';
      return <span key={i} className={`seg ${cls}`}></span>;
    });
    return (
      <div className="mini">
        {segs}
        <span className="lbl" style={{ color: 'var(--crit)' }}>
          stalled
        </span>
      </div>
    );
  }
  if (status === 'cancelled' || status === 'superseded') {
    return (
      <div className="mini">
        <span className="lbl">{status === 'cancelled' ? 'cancelled' : 'superseded'}</span>
      </div>
    );
  }
  if (status === 'observed') {
    return (
      <div className="mini">
        <span className="lbl">observed</span>
      </div>
    );
  }
  return (
    <div className="mini">
      {Array.from({ length: 5 }, (_, i) => (
        <span key={i} className="seg done"></span>
      ))}
      <span className="lbl" style={{ color: 'var(--good)' }}>
        done
      </span>
    </div>
  );
}

export function findCell(status: ReviewStatus, findings: number) {
  if (status === 'reviewing')
    return (
      <span className="findcount some tnum">
        {findings}
        <small>so far</small>
      </span>
    );
  if (status === 'failed' || status === 'cancelled') return <span className="time">—</span>;
  if (findings === 0)
    return (
      <span className="findcount zero tnum">
        0<small>clean</small>
      </span>
    );
  return <span className="findcount some tnum">{findings}</span>;
}

export function stepper(r: ReviewDetail) {
  return (
    <div className="stepper">
      {STAGES.map((name, i) => {
        const st = r.stages[i] || 'pending';
        const cls = st === 'done' ? 'done' : st === 'active' ? 'active' : st === 'failed' ? 'failed' : 'pending';
        const node = st === 'done' ? '✓' : st === 'failed' ? '✕' : st === 'active' ? '' : i + 1;
        const barDone = r.stages[i] === 'done' ? 'done' : '';
        const t = r.timings[i] && r.timings[i].trim() ? r.timings[i] : cls === 'pending' ? '—' : '';
        return (
          <div key={i} className={`step ${cls}`}>
            <div className={`bar ${barDone}`}></div>
            <div className="node">{node}</div>
            <div className="name">{name}</div>
            <div className="t">{t}</div>
          </div>
        );
      })}
    </div>
  );
}

export function findingsCard(r: ReviewDetail) {
  if (r.status === 'failed' || r.status === 'cancelled') {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>{r.status === 'failed' ? 'Why it stalled' : 'Why it stopped'}</h3>
        </div>
        <div className="body">
          <div className={`finding ${r.status === 'failed' ? 'warning' : 'nit'}`}>
            <div className="stripe"></div>
            <div className="fbody">
              <div className="msg" style={{ marginTop: 0 }}>{r.note ?? ''}</div>
            </div>
          </div>
        </div>
      </div>
    );
  }
  if (!r.findingsList.length) {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Findings</h3>
          <span className="badge">0</span>
        </div>
        <div className="body">
          <div className="clean">
            <span className="em mono">✓ clean</span>No issues found in this diff.
          </div>
        </div>
      </div>
    );
  }
  const more =
    r.findings > r.findingsList.length ? (
      <div
        style={{
          textAlign: 'center',
          marginTop: 12,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          color: 'var(--text-3)',
        }}
      >
        + {r.findings - r.findingsList.length} more {r.status === 'reviewing' ? '· still generating' : ''}
      </div>
    ) : null;
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Findings</h3>
        <span className="badge">
          {r.findings}
          {r.status === 'reviewing' ? ' so far' : ''}
        </span>
      </div>
      <div className="body">
        {r.findingsList.map((f: Finding, i: number) => (
          <div key={i} className={`finding ${f.sev}`}>
            <div className="stripe"></div>
            <div className="fbody">
              <div className="frow">
                <span className="sev">{f.sev}</span>
                <span className="loc">{f.loc}</span>
              </div>
              <div className="msg">{f.msg}</div>
            </div>
          </div>
        ))}
        {more}
      </div>
    </div>
  );
}

const EMPTY_USAGE: Usage = { model: '—', prompt: '—', completion: '—', cost: '—', latency: '—' };

export function usageCard(r: ReviewDetail) {
  const u = r.usage ?? EMPTY_USAGE;
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Model usage</h3>
      </div>
      <div className="body">
        <dl className="kv">
          <dt>Model</dt>
          <dd>{u.model}</dd>
          <dt>Prompt tokens</dt>
          <dd>{u.prompt}</dd>
          <dt>Completion tokens</dt>
          <dd>{u.completion}</dd>
          <dt>Latency</dt>
          <dd>{u.latency}</dd>
          <dt>Cost</dt>
          <dd className="accent">{u.cost}</dd>
        </dl>
      </div>
    </div>
  );
}

export function metaCard(r: ReviewDetail) {
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Metadata</h3>
      </div>
      <div className="body">
        <dl className="kv">
          <dt>Review ID</dt>
          <dd style={{ fontSize: 11 }}>{r.id}</dd>
          <dt>Provider</dt>
          <dd>bitbucket-cloud</dd>
          <dt>Target</dt>
          <dd>{r.base}</dd>
          <dt>Head</dt>
          <dd>{r.sha}</dd>
          <dt>Attempt</dt>
          <dd>1</dd>
        </dl>
      </div>
    </div>
  );
}

export function eventsCard(r: ReviewDetail) {
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Event stream</h3>
        <span className="badge">this review only</span>
      </div>
      <div className="body">
        <div className="events">
          {r.events.map((e: ReviewEvent, i: number) => (
            <div key={i} className={`ev ${e.lane}`}>
              <div className="at">{e.at}</div>
              <div className="what">
                <span className="lane"></span>
                <div>
                  <div className="type">{e.type}</div>
                  <div className="det">{e.det}</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
