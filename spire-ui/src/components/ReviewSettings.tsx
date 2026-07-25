import type { ReviewSettings as ReviewSettingsShape } from '../api';
import SettingField from './SettingField';

interface Props {
  value: ReviewSettingsShape;
  disabled?: boolean;
  onChange: (value: ReviewSettingsShape) => void;
}

/**
 * The review pipeline's tuning — fetching the diff, calling the model, posting the comments. Separate
 * from the conversation group because the budgets behave differently: a review that runs out of attempts
 * is reported as failed with the provider's error, while an answer is dead-lettered for replay.
 *
 * Controlled: {@link SettingsGeneral} owns the state and the single Save.
 */
export default function ReviewSettings({ value, disabled, onChange }: Props) {
  return (
    <div className="settings-fields">
      <SettingField
        label="Retry attempts"
        scope="code review"
        hint={
          'How many times a review is attempted when the SCM or LLM fails transiently. Each attempt ' +
          're-runs the whole pipeline from the diff fetch. When they run out the review is reported as ' +
          'failed, carrying the provider’s error — it is not dead-lettered.'
        }
      >
        <input
          type="number"
          min={1}
          max={10}
          value={value.maxAttempts}
          disabled={disabled}
          onChange={(e) => onChange({ ...value, maxAttempts: Number(e.target.value) })}
        />
      </SettingField>

      <SettingField
        scope="code review"
        label="Backoff base (ms)"
        hint={
          'Wait before the second attempt; later attempts multiply it by the factor, capped at 5 minutes. ' +
          'The wait is stored on the review, so it survives a restart rather than being lost.'
        }
      >
        <input
          type="number"
          min={0}
          max={300000}
          step={1000}
          value={value.backoffBaseMs}
          disabled={disabled}
          onChange={(e) => onChange({ ...value, backoffBaseMs: Number(e.target.value) })}
        />
      </SettingField>

      <SettingField
        scope="code review"
        label="Backoff factor"
        hint="Multiplier applied to the wait on each further attempt — 2 doubles it (5s, 10s, 20s…)."
      >
        <input
          type="number"
          min={1}
          max={10}
          step={0.5}
          value={value.backoffFactor}
          disabled={disabled}
          onChange={(e) => onChange({ ...value, backoffFactor: Number(e.target.value) })}
        />
      </SettingField>
    </div>
  );
}
