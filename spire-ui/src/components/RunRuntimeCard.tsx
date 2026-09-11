import type { RunView } from '../api';
import RunCard, { RunFields } from './RunCard';

export default function RunRuntimeCard({ run }: { run: RunView }) {
  return <RunCard title="Runtime"><RunFields rows={[
    ['Provider', run.providerType], ['Base branch', run.baseBranch], ['Base commit', run.baseCommit],
    ['Branch', run.branch], ['Pushed as', run.pushedAs], ['Unit', run.unitId],
  ]} /></RunCard>;
}
