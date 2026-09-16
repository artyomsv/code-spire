import { useMemo, useState } from 'react';
import * as policyApi from '../../work-items/workPolicyApi';
import PhaseStrip from '../../work-items/PhaseStrip';
import SettingField from '../../SettingField';
import { applyPresets, planPresets, PRESETS } from './presets';

interface Props {
  repositoryId: string;
  policy: policyApi.Policy;
  profiles: policyApi.Profile[];
  cancelled: () => void;
  changed: (notice?: string) => void;
  /** Re-read profiles without closing, so a retry after a partial failure reuses what was created. */
  reload: () => void;
}

/**
 * Shows exactly what the presets will create, reuse and leave alone before anything is written, and
 * asks for the ceiling instead of choosing one: the ceiling is the operator's security decision, so the
 * safe default is the most restrictive preset, not the one that makes every label work.
 */
export default function PresetForm({ repositoryId, policy, profiles, cancelled, changed, reload }: Props) {
  const plan = useMemo(() => planPresets(profiles, policy, () => crypto.randomUUID()), [profiles, policy]);
  const presetCeiling = PRESETS.find(preset => preset.name === policy.ceiling?.name)?.name;
  const [ceiling, setCeiling] = useState(presetCeiling ?? PRESETS[0].name);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  async function apply() {
    setBusy(true); setError('');
    try { await applyPresets(repositoryId, policy, plan, ceiling); changed(`Presets applied. Ceiling: ${ceiling}.`); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); setBusy(false); reload(); }
  }
  return <fieldset className="form-lock factory-form" aria-label="Use presets" disabled={busy}>
    <p className="factory-note">Three profiles from the factory design, from most to least careful. Create the same labels in your tracker so people can apply them.</p>
    <ul className="factory-list">{plan.map(step => {
      const profile = step.existing ?? step.created!;
      return <li key={step.preset.label} className="factory-row">
        <span className="chip mono">{step.preset.label}</span><span className="factory-arrow" aria-hidden="true">→</span>
        <span>{profile.name}{step.existing ? ` v${step.existing.version}` : ''}</span><PhaseStrip modes={profile.modes} />
        <span className="prov-sub">{step.conflict ? `kept: already maps to ${step.conflict.name} v${step.conflict.version}`
          : step.existing ? 'uses the existing profile as it is' : `new profile, precedence ${profile.precedence}`}</span>
      </li>;
    })}</ul>
    <SettingField label="Preset ceiling" scope="presets" hint="Required. The most any label may do here. Suggest is the safe start: raise it once you trust the setup.">
      <select aria-label="Preset ceiling" value={ceiling} onChange={event => setCeiling(event.target.value)}>
        {PRESETS.map(preset => <option key={preset.name} value={preset.name}>{preset.name}</option>)}</select></SettingField>
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="prov-actions">
      <button className="btn" type="button" disabled={busy} onClick={() => void apply()}>{busy ? 'Applying…' : 'Apply presets'}</button>
      <button className="btn-ghost" type="button" onClick={cancelled}>Cancel</button></div>
  </fieldset>;
}
