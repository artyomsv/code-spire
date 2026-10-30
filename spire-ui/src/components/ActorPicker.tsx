import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { invalidateLive, writeLive } from '../liveCache';
import { actorLabel, actorPath, actorsKey, deleteActor, fetchActors, resolveActor, saveActor, type Actor, type ActorPolicy } from './actorsApi';
import { fetchRepositories, repositoriesKey } from './repositories/repositoriesApi';

interface Props {
  accountId: string;
  accountType: string;
  repositoryId?: string;
}

/** Person control for account policy and repository /fix effects. */
export default function ActorPicker({ accountId, accountType, repositoryId }: Props) {
  const path = actorPath(accountId, repositoryId);
  const client = useQueryClient();
  const read = useQuery({ queryKey: actorsKey(path), queryFn: () => fetchActors(path) });
  const policy: ActorPolicy | null = read.data ?? null;
  // Save and Remove send the policy's revision: a cached policy is shown, but sent back only once this visit's read
  // answered (PR 359, M-2).
  const current = read.isFetchedAfterMount && read.isSuccess;
  const [draft, setDraft] = useState('');
  const [resolution, setResolution] = useState<{ choices: Actor[]; selected: string; detail: string | null }>({ choices: [], selected: '', detail: null });
  const { choices, selected, detail } = resolution;
  const [effect, setEffect] = useState<'ALLOW' | 'DENY'>('ALLOW');
  const [scope, setScope] = useState('');
  // The member-search repositories, read once the operator asks for them; shared with the repositories page.
  const [scopeAsked, setScopeAsked] = useState(false);
  const repositoriesRead = useQuery({ queryKey: repositoriesKey, queryFn: () => fetchRepositories(), enabled: scopeAsked });
  const repositories = useMemo(
    () => (repositoriesRead.data ?? []).filter(repo => repo.reviewer?.id === accountId || repo.factory?.id === accountId),
    [repositoriesRead.data, accountId],
  );
  const [actionError, setError] = useState<string | null>(null);
  const error = actionError
    ?? (read.error ? String(read.error) : null)
    ?? (scopeAsked && repositoriesRead.error ? String(repositoriesRead.error) : null);
  const [busy, setBusy] = useState(false);

  async function load(refresh = false) {
    if (!refresh) { await invalidateLive(client, actorsKey(path)); return; }
    // The refreshed names are the newest answer: written as the policy, so no plain read replaces them.
    try { writeLive(client, actorsKey(path), await fetchActors(path, true)); }
    catch (failure) { setError(String(failure)); }
  }
  useEffect(() => { setResolution({ choices: [], selected: '', detail: null }); setDraft(''); setError(null); }, [path]);

  async function find() {
    setBusy(true); setError(null); setResolution({ choices: [], selected: '', detail: null });
    try {
      const result = await resolveActor(path, { handle: draft, ...(scope ? { repositoryId: scope } : {}) });
      setResolution({ choices: result.actors, detail: result.detail,
        selected: result.status === 'FOUND' && result.actors.length === 1 ? result.actors[0].providerUserId : '' });
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }
  async function save() {
    if (!policy || !selected) return;
    setBusy(true); setError(null);
    try {
      const existing = policy.actors.find(actor => actor.providerUserId === selected);
      await saveActor(path, { handle: draft, providerUserId: selected, effect,
        revision: repositoryId ? existing?.revision ?? 0 : policy.revision, ...(scope ? { repositoryId: scope } : {}) });
      setDraft(''); setResolution({ choices: [], selected: '', detail: null }); await load();
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }
  function chooseScope() {
    // Each click asks again, as it always did; the cached list fills the select meanwhile.
    if (scopeAsked) void invalidateLive(client, repositoriesKey);
    else setScopeAsked(true);
  }
  return <section aria-label={repositoryId ? 'Fix overrides' : 'Allowed people'}>
    <h4>{repositoryId ? 'Fix overrides' : 'Allowed people'}</h4>
    <p>{repositoryId ? 'Allow or deny this person on this repository. Overrides do not bypass branch protection or spending limits.'
      : 'Enter a person, resolve their identity, then save. Review commands and conversations compare the stored provider identity.'}</p>
    {accountType === 'bitbucket-cloud' && <p>Bitbucket nicknames are not unique. Select a workspace member explicitly. The selected credential needs workspace and user read access. Effective push-permission reads additionally require repository-admin access; this form does not increase it.</p>}
    {accountType === 'atlassian' && <p>Jira Cloud display names are not unique. Select a returned account explicitly. The credential needs Browse users and groups and user-read access. Jira Data Center person lookup is not supported here.</p>}
    <div className="op-form">
    {!repositoryId && accountType === 'bitbucket-cloud' && <>
      <button className="btn-ghost" type="button" onClick={chooseScope}>Choose repository for member search</button>
      <label className="field">Member search repository<select value={scope} onChange={event => { setScope(event.target.value); setResolution({ choices: [], selected: '', detail: null }); }}>
        <option value="">Select a bound repository</option>{repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}
      </select></label>
    </>}
    <label className="field">Person<input placeholder="@handle or display name" value={draft} onChange={event => { setDraft(event.target.value); setResolution({ choices: [], selected: '', detail: null }); }} /></label>
    <button className="btn-ghost" type="button" disabled={busy || !draft.trim()} onClick={() => void find()}>Resolve person</button>
    {detail && <p>{detail}</p>}
    {choices.length > 0 && <label className="field">Resolved person<select value={selected} onChange={event => setResolution(previous => ({ ...previous, selected: event.target.value }))}>
      <option value="">Select a person</option>{choices.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.displayName} · {actor.providerUserId}</option>)}
    </select></label>}
    {repositoryId && <label className="field">Policy effect<select value={effect} onChange={event => setEffect(event.target.value as 'ALLOW' | 'DENY')}><option value="ALLOW">Allow</option><option value="DENY">Deny</option></select></label>}
    <button className="btn-ghost" type="button" disabled={busy || !selected || !current} onClick={() => void save()}>Save person</button>
    </div>
    {error && <p className="prov-error" role="alert">{error}</p>}
    {policy ? <ul>{policy.actors.map(actor => <li key={actor.providerUserId}>
      <span>{actorLabel(actor)}</span> — {actor.effect === 'DENY' ? 'Denied' : 'Allowed'}{actor.stale && <span> · stale display; refresh by ID</span>}
      <button className="btn-ghost" type="button" disabled={busy || !current} onClick={async () => {
        setBusy(true); setError(null);
        try { await deleteActor(path, actor, repositoryId ? actor.revision : policy.revision); await load(); }
        catch (failure) { setError(String(failure)); } finally { setBusy(false); }
      }}>Remove {actorLabel(actor)}</button>
    </li>)}</ul> : <p>People have not loaded.</p>}
    <button className="btn-ghost" type="button" disabled={busy} onClick={() => { setError(null); void load(true); }}>Reload people and refresh names</button>
  </section>;
}
