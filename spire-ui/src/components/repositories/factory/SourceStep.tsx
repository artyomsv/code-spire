import { useState } from 'react';
import type { ProviderView, WebhookRepoView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import * as api from '../../work-items/workSourcesApi';
import FactoryStep from './FactoryStep';
import InstantUpdates from './InstantUpdates';
import { CreateSourceForm, EditSourceForm, trackerNames } from './SourceForms';

export interface SourceStepProps {
  repository: Repository;
  sources: api.WorkSource[];
  accounts: ProviderView[];
  webhooks: { hooks: WebhookRepoView[]; unavailable: boolean; changed: (hooks: WebhookRepoView[]) => void };
  /** Which form is open anywhere in the tab; only one part changes at a time. */
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
}

export default function SourceStep({ repository, sources, accounts, webhooks, open, setOpen, changed }: SourceStepProps) {
  const [scanError, setScanError] = useState('');
  const [scanning, setScanning] = useState<string | null>(null);
  const reading = sources.some(source => source.enabled);
  const adding = open === 'source:new';
  const accountName = (id: string) => accounts.find(account => account.id === id)?.name ?? 'an unavailable account';
  // The request only sets a flag; the scanner picks it up on its next sweep, about half a minute
  // later. So the button reports the request, and never claims the scan itself has finished.
  async function rescan(source: api.WorkSource) {
    setScanError(''); setScanning(source.id);
    try { await api.rescanWorkSource(source.id); changed(`Scan requested for ${source.name}. The scanner reads it within about 30 seconds.`); }
    catch (failure) { setScanError(String(failure)); }
    finally { setScanning(null); }
  }
  return <FactoryStep number={1} question="Where tickets come from" term="work source"
    state={open?.startsWith('source') ? 'editing' : reading ? 'done' : 'missing'}
    actions={!adding && <button className={sources.length ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null || !repository.enabled}
      onClick={() => setOpen('source:new')}>{sources.length ? 'Add another source' : 'Add source'}</button>}>
    {!repository.enabled && <p className="factory-note">This repository is disabled. Enable it on the Details tab before it can take tickets.</p>}
    {sources.length === 0 && !adding && <p className="factory-note">Nothing reads tickets for this repository. Add the tracker whose labels should start work.</p>}
    <ul className="factory-list">{sources.map(source => <li key={source.id}>
      {open === `source:${source.id}`
        ? <EditSourceForm key={`${source.id}:${source.version.source}`} source={source} repository={repository} accounts={accounts}
          saved={() => changed(`Work source ${source.name} saved.`)} cancelled={() => setOpen(null)} />
        : <>
          <div className="factory-row">
            <span><b>{source.name}</b> — {trackerNames[source.type] ?? 'Unknown tracker'} {source.type === 'JIRA' ? 'project' : 'issues in'} <span className="mono">{source.scope}</span>,
              read with <span className="mono">{accountName(source.accountId)}</span></span>
            <span className="grow" />
            <span className={`chip ${source.enabled ? 'ok' : 'no'}`}>{source.enabled ? source.health.split('_').join(' ') : 'unavailable'}</span>
            <button className="btn-ghost sm" type="button" disabled={open !== null || !source.enabled || scanning !== null} onClick={() => void rescan(source)}
              aria-label={`Scan ${source.name} now`}>{scanning === source.id ? 'Asking…' : 'Scan now'}</button>
            <button className="btn-ghost sm" type="button" disabled={open !== null} onClick={() => setOpen(`source:${source.id}`)}
              aria-label={`Edit ${source.name}`}>Edit</button>
          </div>
          {!source.enabled && source.configuredEnabled && <p className="factory-note">This source is enabled, but its account or repository is unavailable.</p>}
          <InstantUpdates repository={repository} source={source} hooks={webhooks.hooks} hooksUnavailable={webhooks.unavailable} onHooksChanged={webhooks.changed} />
        </>}
    </li>)}</ul>
    {scanError && <p className="prov-error" role="alert">{scanError}</p>}
    {adding && <CreateSourceForm repository={repository} accounts={accounts} cancelled={() => setOpen(null)}
      saved={created => changed(`Work source ${created.name} registered.`)} />}
  </FactoryStep>;
}
