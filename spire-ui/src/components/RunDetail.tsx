import { Link, useParams } from 'react-router';
import { useRunDetail } from '../hooks/useRunDetail';
import RunCard from './RunCard';
import RunPhases from './RunPhases';
import RunRuntimeCard from './RunRuntimeCard';
import RunSpendCard from './RunSpendCard';
import RunDefinitionCard from './RunDefinitionCard';
import RunTaskCard from './RunTaskCard';
import RunEventStream from './RunEventStream';
import { ProposalCell, runStatusLabel, runStatusPill, shortRunId } from './Runs';

export default function RunDetail() {
  const splat = useParams()['*'] ?? '';
  // React Router decodes path segments, including the encoded id from the list, for useParams.
  // Decoding that value again would corrupt a legitimate literal %41 in an id.
  return <RunDetailPage key={splat} runId={splat} />;
}

function RunDetailPage({ runId }: { runId: string }) {
  const { run, loading, error } = useRunDetail(runId);
  return <div className="content">
    <nav className="run-breadcrumb" aria-label="Breadcrumb"><Link to="/runs">Runs</Link> / {shortRunId(runId)}</nav>
    {error && <p role="alert">{error}</p>}
    {loading && <p className="prov-sub">Loading run…</p>}
    {!loading && !error && !run && <p>No such run</p>}
    {run && <>
      <header className="run-heading">
        <h2 className="mono">{run.runId}</h2>
        <div className="run-heading-meta">
          <span className={`pill ${runStatusPill(run.status)}`}><span className="glyph" />{runStatusLabel(run.status)}</span>
          <span className="run-delivery">{run.prUrl || run.prError ? <ProposalCell run={run} /> : run.pushedRef ?? 'No delivery recorded'}</span>
        </div>
        <p className="prov-sub">{run.harness} · {run.workspace}/{run.slug} · {run.model}</p>
      </header>
      <div className="run-detail-grid">
        <div className="run-main">
          <RunPhases run={run} />
          <RunEventStream runId={runId} />
          {run.status === 'push_gate_refused' && <RunCard title="Blocked paths">
            <ul>{run.blocked.map((change, index) => <li key={`${change.path}-${index}`}>
              <code>{change.path}</code> <span className="prov-sub">{change.kind}</span>
            </li>)}</ul>
          </RunCard>}
          {run.status === 'failed' && <RunCard title="Failure">
            <p>{run.failureCause ?? 'Unknown cause'}</p><p className="run-task">{run.failureDetail ?? 'not recorded'}</p>
          </RunCard>}
        </div>
        <aside className="run-sidebar">
          <RunRuntimeCard run={run} /><RunSpendCard run={run} /><RunDefinitionCard run={run} />
          <RunTaskCard key={run.taskSummary} summary={run.taskSummary} />
        </aside>
      </div>
    </>}
  </div>;
}
