import { Link } from 'react-router';
import type { WorkItemDetail } from '../../api';
import { WorkflowStatus, workReason } from './WorkItems';

export default function WorkItemJourney({ item }: { item: Pick<WorkItemDetail, 'phase' | 'workflowStatus' | 'reason' | 'preparation' | 'builds' | 'gate' | 'events'> }) {
  const builds = item.builds ?? [];
  return <section aria-label="Work item journey" className="work-item-journey">
    <h3>Workflow</h3><WorkflowStatus status={item.workflowStatus} />
    <p>Phase: {item.phase}</p><p>{workReason(item.reason)}</p>
    <p>Runs recorded: {builds.filter(build => build.runId !== null).length}</p>
    {item.preparation && <>
      <h4>Prepared task</h4>
      <p><a href={item.preparation.specification.location.link} target="_blank" rel="noreferrer">Specification {item.preparation.specification.location.issueKey}</a> · <a href={item.preparation.plan.location.link} target="_blank" rel="noreferrer">Plan {item.preparation.plan.location.issueKey}</a></p>
      <p>Base: {item.preparation.baseBranch} at <code>{item.preparation.baseCommit}</code></p>
      <details><summary>Registered artifact versions</summary><p>Specification: <code>{item.preparation.specification.sha256}</code></p><p>Plan: <code>{item.preparation.plan.sha256}</code></p></details>
    </>}
    {item.gate?.state === 'OPEN' && <p>Waiting on {item.gate.phase} approval. <Link to="/approvals">Review this decision</Link></p>}
    <ul>{item.events.filter(event => event.type === 'GATE_RESOLVED').map(event => <li key={event.sequence}>
      {event.phase} decision: {event.gateState} by {event.resolver}
    </li>)}</ul>
    {builds.length > 0 && <ul aria-label="Build dispatches">{builds.map(build => <li key={build.attemptId}>
      {build.runId ? <Link to={`/runs/${encodeURIComponent(build.runId)}`}>Open run</Link> : 'Build dispatch pending'} · {build.state}
      {build.reason && <> · {workReason(build.reason)}</>}
    </li>)}</ul>}
  </section>;
}
