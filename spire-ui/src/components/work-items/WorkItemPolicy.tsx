import type { WorkItemDetail } from '../../api';
import { workReason } from './WorkItems';

export default function WorkItemPolicy({ item }: { item: Pick<WorkItemDetail, 'profile' | 'ceiling' | 'policyReason' | 'effectiveModes' | 'admittedModes' | 'effectiveLimits'> }) {
  return <section aria-label="Work item policy">
    <p>{item.profile ? `Selected profile: ${item.profile.name} v${item.profile.version}` : 'No profile selected'}</p>
    <p>{item.ceiling ? `Repository ceiling: ${item.ceiling.name} v${item.ceiling.version}` : 'No repository ceiling configured'}</p>
    <p>{workReason(item.policyReason)}</p>
    <table className="prov-table"><thead><tr><th>Phase</th><th>Effective mode</th><th>Mode at admission</th></tr></thead>
      <tbody>{Object.entries(item.effectiveModes).map(([phase, mode]) =>
        <tr key={phase}><th>{phase.toLowerCase()}</th><td>{mode}</td><td>{item.admittedModes[phase]}</td></tr>)}</tbody></table>
    {item.effectiveLimits && <>
      <h3>Effective limits</h3>
      <dl><dt>Approval lifetime</dt><dd>{item.effectiveLimits.gateTtlSeconds} seconds</dd>
        <dt>Runs per item</dt><dd>{item.effectiveLimits.maxRunsPerItem}</dd><dt>Steps per plan</dt><dd>{item.effectiveLimits.maxStepsPerPlan}</dd>
        <dt>Wall time</dt><dd>{item.effectiveLimits.maxWallClockSeconds} seconds</dd><dt>Cost</dt><dd>{item.effectiveLimits.maxCostMillicents} millicents</dd>
        <dt>Calls per item</dt><dd>{item.effectiveLimits.maxCallsPerItem}</dd></dl>
      <h4>Protected paths, including the CI floor</h4><ul>{item.effectiveLimits.protectedPaths.map(path => <li key={path}>{path}</li>)}</ul>
    </>}
  </section>;
}
