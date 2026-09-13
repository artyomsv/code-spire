import { useEffect, useState } from 'react';
import { actorLabel, actorPath, deleteActor, fetchActors, resolveActor, saveActor, type Actor, type ActorPolicy } from './actorsApi';
import { fetchRepositories, type Repository } from './repositories/repositoriesApi';

interface Props {
  accountId: string;
  accountType: string;
  repositoryId?: string;
}

/** Person control for account policy and repository /fix effects. */
export default function ActorPicker({ accountId, accountType, repositoryId }: Props) {
  const path = actorPath(accountId, repositoryId);
  const [policy, setPolicy] = useState<ActorPolicy | null>(null);
  const [draft, setDraft] = useState('');
  const [resolution, setResolution] = useState<{ choices: Actor[]; selected: string; detail: string | null }>({ choices: [], selected: '', detail: null });
  const { choices, selected, detail } = resolution;
  const [effect, setEffect] = useState<'ALLOW' | 'DENY'>('ALLOW');
  const [scope, setScope] = useState('');
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load(refresh = false) {
    try { setPolicy(await fetchActors(path, refresh)); }
    catch (failure) { setError(String(failure)); }
  }
  useEffect(() => { setPolicy(null); setResolution({ choices: [], selected: '', detail: null }); setDraft(''); setError(null); void load(); }, [path]);

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
  async function chooseScope() {
    try { setRepositories((await fetchRepositories()).filter(repo => repo.reviewer?.id === accountId || repo.factory?.id === accountId)); }
    catch (failure) { setError(String(failure)); }
  }
  return <section aria-label={repositoryId ? 'Fix overrides' : 'Allowed people'}>
    <h4>{repositoryId ? 'Fix overrides' : 'Allowed people'}</h4>
    <p>{repositoryId ? 'Allow or deny this person on this repository. Overrides do not bypass branch protection or spending limits.'
      : 'Enter a person, resolve their identity, then save. Review commands and conversations compare the stored provider identity.'}</p>
    {accountType === 'bitbucket-cloud' && <p>Bitbucket nicknames are not unique. Select a workspace member explicitly. The selected credential needs workspace and user read access. Effective push-permission reads additionally require repository-admin access; this form does not increase it.</p>}
    {accountType === 'atlassian' && <p>Jira Cloud display names are not unique. Select a returned account explicitly. The credential needs Browse users and groups and user-read access. Jira Data Center person lookup is not supported here.</p>}
    {!repositoryId && accountType === 'bitbucket-cloud' && <>
      <button type="button" onClick={() => void chooseScope()}>Choose repository for member search</button>
      <label>Member search repository<select value={scope} onChange={event => { setScope(event.target.value); setResolution({ choices: [], selected: '', detail: null }); }}>
        <option value="">Select a bound repository</option>{repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.workspace}/{repo.slug}</option>)}
      </select></label>
    </>}
    <label>Person<input placeholder="@handle or display name" value={draft} onChange={event => { setDraft(event.target.value); setResolution({ choices: [], selected: '', detail: null }); }} /></label>
    <button type="button" disabled={busy || !draft.trim()} onClick={() => void find()}>Resolve person</button>
    {detail && <p>{detail}</p>}
    {choices.length > 0 && <label>Resolved person<select value={selected} onChange={event => setResolution(previous => ({ ...previous, selected: event.target.value }))}>
      <option value="">Select a person</option>{choices.map(actor => <option key={actor.providerUserId} value={actor.providerUserId}>{actorLabel(actor)} · {actor.displayName} · {actor.providerUserId}</option>)}
    </select></label>}
    {repositoryId && <label>Policy effect<select value={effect} onChange={event => setEffect(event.target.value as 'ALLOW' | 'DENY')}><option value="ALLOW">Allow</option><option value="DENY">Deny</option></select></label>}
    <button type="button" disabled={busy || !selected || !policy} onClick={() => void save()}>Save person</button>
    {error && <p role="alert">{error}</p>}
    {policy ? <ul>{policy.actors.map(actor => <li key={actor.providerUserId}>
      <span>{actorLabel(actor)}</span> — {actor.effect === 'DENY' ? 'Denied' : 'Allowed'}{actor.stale && <span> · stale display; refresh by ID</span>}
      <button type="button" disabled={busy} onClick={async () => {
        setBusy(true); setError(null);
        try { await deleteActor(path, actor, repositoryId ? actor.revision : policy.revision); await load(); }
        catch (failure) { setError(String(failure)); } finally { setBusy(false); }
      }}>Remove {actorLabel(actor)}</button>
    </li>)}</ul> : <p>People have not loaded.</p>}
    <button type="button" disabled={busy} onClick={() => { setError(null); void load(true); }}>Reload people and refresh names</button>
  </section>;
}
