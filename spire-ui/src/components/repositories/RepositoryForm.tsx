import { useState } from 'react';
import type { ProviderView } from '../../api';
import { saveRepository, type Repository, type RepositoryInput } from './repositoriesApi';

interface Props {
  initial: Repository | null;
  providers: ProviderView[];
  kinds: string[];
  onSaved: (repository: Repository) => void;
  onCancel: () => void;
}
function origin(value: string): string {
  try { return new URL(value).origin; } catch { return ''; }
}

/** Coordinates identify the repository; account choices never silently follow a workspace match. */
export default function RepositoryForm({ initial, providers, kinds, onSaved, onCancel }: Props) {
  const [fields, setFields] = useState<RepositoryInput>({
    scmType: initial?.scmType ?? '', forgeOrigin: initial?.forgeOrigin ?? '',
    workspace: initial?.workspace ?? '', slug: initial?.slug ?? '', enabled: initial?.enabled ?? true,
    reviewerAccountId: initial?.reviewer?.id ?? null, factoryAccountId: initial?.factory?.id ?? null,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const patch = (next: Partial<RepositoryInput>) => setFields(previous => ({ ...previous, ...next }));
  const compatible = providers.filter(p => p.type === fields.scmType && origin(p.baseUrl) === origin(fields.forgeOrigin));

  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(null);
    try { onSaved(await saveRepository(fields, initial)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  }
  return (
    <form onSubmit={submit} style={{ padding: 18, display: 'grid', gap: 12 }}>
      <h3>{initial ? 'Repository details' : 'Register repository'}</h3>
      <label>Forge kind <select aria-label="Forge kind" required disabled={!!initial} value={fields.scmType}
        onChange={e => patch({ scmType: e.target.value, reviewerAccountId: null, factoryAccountId: null })}>
        <option value="">Select forge</option>{kinds.map(kind => <option key={kind}>{kind}</option>)}
      </select></label>
      <label>Forge origin <input aria-label="Forge origin" type="url" required readOnly={!!initial} value={fields.forgeOrigin}
        onChange={e => patch({ forgeOrigin: e.target.value, reviewerAccountId: null, factoryAccountId: null })} /></label>
      <label>Workspace <input aria-label="Workspace" required readOnly={!!initial} value={fields.workspace}
        onChange={e => patch({ workspace: e.target.value })} /></label>
      <label>Repository slug <input aria-label="Repository slug" required readOnly={!!initial} value={fields.slug}
        onChange={e => patch({ slug: e.target.value })} /></label>
      {(['REVIEWER', 'FACTORY'] as const).map(role => {
        const key = role === 'REVIEWER' ? 'reviewerAccountId' : 'factoryAccountId';
        return <label key={role}>{role === 'REVIEWER' ? 'Reviewer account' : 'Factory account'}
          <select aria-label={`${role} account`} value={fields[key] ?? ''} onChange={e => patch({ [key]: e.target.value || null })}>
            <option value="">No account selected</option>
            {compatible.filter(p => p.role === role).map(p => <option key={p.id} value={p.id}>
              {p.name}{p.enabled ? '' : ' (disabled)'}
            </option>)}
          </select>
        </label>;
      })}
      <label><input type="checkbox" checked={fields.enabled} onChange={e => patch({ enabled: e.target.checked })} /> Enabled</label>
      {error && <p role="alert">{error}</p>}
      <div><button className="btn" disabled={busy} type="submit">{busy ? 'Saving…' : 'Save repository'}</button>
        <button className="btn" type="button" onClick={onCancel}>Cancel</button></div>
    </form>
  );
}
