import { useState } from 'react';
import type { ProviderView } from '../../api';
import { saveRepository, type Repository, type RepositoryInput } from './repositoriesApi';
import SettingField from '../SettingField';
import SidePanel from '../SidePanel';
import RepositoryAccountSelects from './RepositoryAccountSelects';
import RepositorySwitches from './RepositorySwitches';

interface Props {
  providers: ProviderView[];
  kinds: string[];
  onSaved: (repository: Repository) => void;
  onCancel: () => void;
  prefill?: URLSearchParams;
}

/**
 * Registers a repository. Coordinates identify it; account choices never silently follow a workspace
 * match. A registered repository is edited in the Settings tab of its own panel (RepositorySettings).
 */
export default function RepositoryForm({ providers, kinds, onSaved, onCancel, prefill = new URLSearchParams() }: Props) {
  const [fields, setFields] = useState<RepositoryInput>({
    scmType: prefill.get('scmType') ?? '', forgeOrigin: prefill.get('forgeOrigin') ?? '',
    workspace: prefill.get('workspace') ?? '', slug: prefill.get('slug') ?? '', enabled: true,
    reviewerAccountId: null, factoryAccountId: null, reviewerMayApprove: true, reviewLlmProviderId: null, reviewHarness: null,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const patch = (next: Partial<RepositoryInput>) => setFields(previous => ({ ...previous, ...next }));

  async function submit() {
    setBusy(true); setError(null);
    try { onSaved(await saveRepository(fields, null)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  }
  return (
    <SidePanel title="Register repository" busy={busy} onClose={onCancel} actions={<>
      <button className="btn" disabled={busy} type="button" onClick={() => void submit()}>{busy ? 'Saving…' : 'Save repository'}</button>
      <button className="btn-ghost" type="button" onClick={onCancel}>Cancel</button></>}>
      {prefill.get('registration') && <p className="prov-note">Incoming registration: <span className="mono">{prefill.get('registration')}</span></p>}
      <SettingField label="Forge kind" scope="repository" hint="Required. Which forge hosts this repository.">
        <select aria-label="Forge kind" required value={fields.scmType}
          onChange={e => patch({ scmType: e.target.value, reviewerAccountId: null, factoryAccountId: null })}>
          <option value="">Select forge</option>{kinds.map(kind => <option key={kind}>{kind}</option>)}
        </select></SettingField>
      <SettingField label="Forge origin" scope="repository"
        hint="Required. The forge API origin, for example https://api.github.com. It is what tells two hosts with the same workspace apart.">
        <input aria-label="Forge origin" type="url" required value={fields.forgeOrigin}
          onChange={e => patch({ forgeOrigin: e.target.value, reviewerAccountId: null, factoryAccountId: null })} /></SettingField>
      <SettingField label="Workspace" scope="repository" hint="Required. The owner, organisation or group that contains the repository.">
        <input aria-label="Workspace" required value={fields.workspace} onChange={e => patch({ workspace: e.target.value })} /></SettingField>
      <SettingField label="Repository slug" scope="repository" hint="Required. The repository name within the workspace.">
        <input aria-label="Repository slug" required value={fields.slug} onChange={e => patch({ slug: e.target.value })} /></SettingField>
      <RepositoryAccountSelects fields={fields} providers={providers} onChange={patch} />
      <RepositorySwitches fields={fields} onChange={patch} />
      {error && <p className="prov-error" role="alert">{error}</p>}
    </SidePanel>
  );
}
