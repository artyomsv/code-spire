import type { PayWith, Preparation } from './buildDefaultsApi';

interface Props {
  repositoryId: string;
  value: Preparation;
  /** The runner a generated preparation uses: the same harness, model and payment as the build (§6.1). */
  harness: string;
  model: string;
  payWith: PayWith;
  onChange: (preparation: Preparation) => void;
}

const LABEL: Record<Preparation, string> = { composed: 'Copy the ticket as one step', generated: 'Write them with the build model' };

/**
 * How the specification and plan are made (M4 slice 3). Shown only when the server offers `generated`, so
 * a deployment that cannot write them never shows a choice it would refuse.
 */
export default function PreparationField({ repositoryId, value, harness, model, payWith, onChange }: Props) {
  const runner = `runs on ${harness || 'the harness above'} · ${model || 'the model above'} · ${payWith === 'SUBSCRIPTION' ? 'seat' : 'API key'}`;
  return <fieldset className="factory-form" aria-label="Specification and plan">
    <legend className="field-label">Specification and plan</legend>
    {(Object.keys(LABEL) as Preparation[]).map(preparation => <label key={preparation} className="field-check">
      <input type="radio" name={`preparation-${repositoryId}`} value={preparation} checked={value === preparation}
        onChange={() => onChange(preparation)} />
      <span>{LABEL[preparation]}{preparation === 'generated' && <span className="prov-sub"> {runner}</span>}</span></label>)}
    {/* A20: under profiles where spec and plan run without approval, this spends before anyone is asked. */}
    <p className="factory-note">Before the first approval this spends two short runs, and up to one automatic retry each, when the
      specification and plan phases are set to run without approval.</p>
  </fieldset>;
}
