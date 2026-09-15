import { useEffect, useState } from 'react';
import type { ProviderView, WebhookRepoView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import * as sourcesApi from '../../work-items/workSourcesApi';
import * as policyApi from '../../work-items/workPolicyApi';
import { readiness } from './factoryModel';
import FactoryOutcome from './FactoryOutcome';
import SourceStep from './SourceStep';
import PeopleStep from './PeopleStep';
import { CeilingStep, LabelsStep } from './PolicySteps';

interface Props {
  repository: Repository;
  accounts: ProviderView[];
  webhooks: { hooks: WebhookRepoView[]; unavailable: boolean; changed: (hooks: WebhookRepoView[]) => void };
  /** Tells the list its setup column is out of date. */
  onChanged: () => void;
}

interface Loaded { sources: sourcesApi.WorkSource[]; profiles: policyApi.Profile[]; policy: policyApi.Policy }

/**
 * Everything that lets a ticket start work on one repository, in the order it has to exist. Each part
 * used to be its own screen, and an operator could set all of them without seeing how they combine;
 * here the combination is stated first and every part is changed where it is shown.
 */
export default function RepositoryFactory({ repository, accounts, webhooks, onChanged }: Props) {
  const [data, setData] = useState<Loaded | null>(null);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    Promise.all([sourcesApi.fetchWorkSources(), policyApi.profiles(), policyApi.policy(repository.id)]).then(([sources, profiles, policy]) => {
      if (!active) return;
      setData({ sources: sources.filter(source => source.repositoryId === repository.id), profiles, policy }); setError('');
    }).catch(failure => { if (active) setError(String(failure)); });
    return () => { active = false; };
  }, [repository.id, refresh]);

  const reload = () => setRefresh(value => value + 1);
  const changed = (message?: string) => { setOpen(null); setNotice(message ?? ''); reload(); onChanged(); };
  const openStep = (next: string | null) => { setNotice(''); setOpen(next); };
  if (error) return <p className="prov-error" role="alert">{error}</p>;
  if (!data) return <p className="factory-note">Loading factory setup…</p>;

  const shared = { open, setOpen: openStep, changed };
  // Keyed by revision: a form opened after a save must start from what was saved, not what was typed before.
  const policyKey = `${data.policy.revision}:${data.profiles.length}`;
  return <div className="factory">
    <FactoryOutcome ready={readiness(data.sources, data.policy)} sources={data.sources} policy={data.policy} />
    {notice && <p className="factory-note" role="status">{notice}</p>}
    <ol className="factory-steps">
      <SourceStep repository={repository} sources={data.sources} accounts={accounts} webhooks={webhooks} {...shared} />
      <PeopleStep sources={data.sources} {...shared} />
      <CeilingStep key={`ceiling:${policyKey}`} repositoryId={repository.id} policy={data.policy} profiles={data.profiles} reload={reload} {...shared} />
      <LabelsStep key={`labels:${policyKey}`} repositoryId={repository.id} policy={data.policy} profiles={data.profiles} reload={reload} {...shared} />
    </ol>
  </div>;
}
