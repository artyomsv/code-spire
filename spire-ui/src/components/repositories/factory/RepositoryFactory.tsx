import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { ProviderView, WebhookRepoView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import * as sourcesApi from '../../work-items/workSourcesApi';
import * as policyApi from '../../work-items/workPolicyApi';
import { readiness } from './factoryModel';
import FactoryOutcome from './FactoryOutcome';
import SourceStep from './SourceStep';
import PeopleStep from './PeopleStep';
import { CeilingStep, LabelsStep } from './PolicySteps';
import BuildStep from './BuildStep';
import { buildDefaults, buildDefaultsKey, type BuildDefaults } from './buildDefaultsApi';
import LandStep from './LandStep';
import { landSettings, landSettingsKey, type LandSettings } from './landSettingsApi';
import { policyKeys } from '../../work-items/workQueries';

interface Props {
  repository: Repository;
  accounts: ProviderView[];
  webhooks: { hooks: WebhookRepoView[]; unavailable: boolean; changed: (hooks: WebhookRepoView[]) => void };
}

interface Loaded { sources: sourcesApi.WorkSource[]; profiles: policyApi.Profile[]; policy: policyApi.Policy; build: BuildDefaults; land: LandSettings }

/**
 * Everything that lets a ticket start work on one repository, in the order it has to exist. Each part
 * used to be its own screen, and an operator could set all of them without seeing how they combine;
 * here the combination is stated first and every part is changed where it is shown.
 */
export default function RepositoryFactory({ repository, accounts, webhooks }: Props) {
  const client = useQueryClient();
  const [notice, setNotice] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  // Keys shared with the repositories list, the profiles screen and the preparation form: a save here reaches them all.
  // Each is per repository except the sources, one list for every repository, filtered below.
  const buildKey = ['factory-build', repository.id, buildDefaultsKey(repository.id)[0]];
  const keys = [sourcesApi.workSourcesKey, policyKeys.profiles(), policyKeys.policy(repository.id), buildKey,
    landSettingsKey(repository.id)];
  const sourcesRead = useQuery({ queryKey: sourcesApi.workSourcesKey, queryFn: () => sourcesApi.fetchWorkSources() });
  const profilesRead = useQuery({ queryKey: policyKeys.profiles(), queryFn: () => policyApi.profiles() });
  const policyRead = useQuery({ queryKey: policyKeys.policy(repository.id), queryFn: () => policyApi.policy(repository.id) });
  const buildRead = useQuery({ queryKey: buildKey, queryFn: () => buildDefaults(repository.id) });
  const landRead = useQuery({ queryKey: landSettingsKey(repository.id), queryFn: () => landSettings(repository.id) });
  const failure = sourcesRead.error ?? profilesRead.error ?? policyRead.error ?? buildRead.error ?? landRead.error;
  const error = failure ? String(failure) : '';
  // Shown once all five answered. A re-read that fails afterwards keeps them on screen and says so above them.
  const data: Loaded | null = sourcesRead.data !== undefined && profilesRead.data !== undefined && policyRead.data !== undefined
    && buildRead.data !== undefined && landRead.data !== undefined
    ? { sources: sourcesRead.data.filter(source => source.repositoryId === repository.id), profiles: profilesRead.data,
      policy: policyRead.data, build: buildRead.data, land: landRead.data } : null;

  const reload = () => { for (const queryKey of keys) void client.invalidateQueries({ queryKey, exact: true }); };
  const changed = (message?: string) => { setOpen(null); setNotice(message ?? ''); reload(); };
  const openStep = (next: string | null) => { setNotice(''); setOpen(next); };
  if (error) return <p className="prov-error" role="alert">{error}</p>;
  if (!data) return <p className="factory-note">Loading factory setup…</p>;

  const shared = { open, setOpen: openStep, changed };
  // Keyed by revision: a form opened after a save must start from what was saved, not what was typed before.
  const policyKey = `${data.policy.revision}:${data.profiles.length}`;
  return <div className="factory">
    {error && <p className="prov-error" role="alert">{error}</p>}
    <FactoryOutcome ready={readiness(data.sources, data.policy)} sources={data.sources} policy={data.policy} build={data.build} />
    {notice && <p className="factory-note" role="status">{notice}</p>}
    <ol className="factory-steps">
      <SourceStep repository={repository} sources={data.sources} accounts={accounts} webhooks={webhooks} {...shared} />
      <PeopleStep sources={data.sources} {...shared} />
      <CeilingStep key={`ceiling:${policyKey}`} repositoryId={repository.id} policy={data.policy} profiles={data.profiles} reload={reload} {...shared} />
      <LabelsStep key={`labels:${policyKey}`} repositoryId={repository.id} policy={data.policy} profiles={data.profiles} reload={reload} {...shared} />
      <BuildStep key={`build:${data.build.revision}`} repositoryId={repository.id} defaults={data.build} reload={reload} {...shared} />
      <LandStep key={`land:${data.land.revision}`} repositoryId={repository.id} settings={data.land} reload={reload} {...shared} />
    </ol>
  </div>;
}
