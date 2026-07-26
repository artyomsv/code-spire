import type { ConversationLevel, ConversationSettings as ConversationSettingsShape } from '../api';
import Select from './Select';
import SettingField from './SettingField';

const LEVELS: ConversationLevel[] = ['REPORT_ONLY', 'EXPLAIN', 'INTERACTIVE'];

const LABELS: Record<ConversationLevel, string> = {
  REPORT_ONLY: 'Report-only — post findings, ignore replies',
  EXPLAIN: 'Explain — answer and defend findings in-thread',
  INTERACTIVE: 'Interactive — can be convinced (Plan 2)',
};

/** Coerce any value to a valid level; unknown falls back to REPORT_ONLY (the fail-safe default). */
export function normalizeLevel(value: unknown): ConversationLevel {
  const v = String(value).trim().toUpperCase();
  return v === 'EXPLAIN' || v === 'INTERACTIVE' ? (v as ConversationLevel) : 'REPORT_ONLY';
}

interface Props {
  value: ConversationSettingsShape;
  disabled?: boolean;
  onChange: (value: ConversationSettingsShape) => void;
}

/**
 * How the bot replies to comments: the default interaction level plus the per-thread turn cap and the
 * retry/backoff applied to answering. These are NOT the review pipeline's retries — an answer that runs
 * out of attempts is dead-lettered for replay.
 *
 * Controlled: {@link SettingsGeneral} owns the state and the single Save.
 */
export default function ConversationSettings({ value, disabled, onChange }: Props) {
  function update<K extends keyof ConversationSettingsShape>(key: K, v: ConversationSettingsShape[K]) {
    onChange({ ...value, [key]: v });
  }

  return (
    <div className="settings-fields">
      <SettingField
        scope="conversation"
        label="Interaction level"
        hint="The default applied to every provider that doesn’t set its own conversation level."
      >
        <Select
          ariaLabel="Interaction level"
          value={value.level}
          disabled={disabled}
          options={LEVELS.map((l) => ({ value: l, label: LABELS[l] }))}
          onChange={(v) => update('level', normalizeLevel(v))}
        />
      </SettingField>

      <SettingField scope="conversation" label="Turn cap" hint="Maximum bot replies in one thread before it defers to the team.">
        <input
          type="number"
          min={1}
          max={20}
          value={value.turnCap}
          disabled={disabled}
          onChange={(e) => update('turnCap', Number(e.target.value))}
        />
      </SettingField>

      <SettingField
        scope="conversation"
        label="Retry attempts"
        hint={
          'Attempts at answering a reply when the SCM or LLM fails transiently. When they run out the ' +
          'answer is dead-lettered so it can be replayed once the provider recovers. Review retries are ' +
          'set under Code review.'
        }
      >
        <input
          type="number"
          min={1}
          max={10}
          value={value.maxAttempts}
          disabled={disabled}
          onChange={(e) => update('maxAttempts', Number(e.target.value))}
        />
      </SettingField>

      <SettingField scope="conversation" label="Backoff base (ms)" hint="Wait before the second attempt at answering; later attempts multiply it by the factor.">
        <input
          type="number"
          min={100}
          max={60000}
          step={100}
          value={value.backoffBaseMs}
          disabled={disabled}
          onChange={(e) => update('backoffBaseMs', Number(e.target.value))}
        />
      </SettingField>

      <SettingField scope="conversation" label="Backoff factor" hint="Multiplier applied to the wait on each further attempt (2 doubles it), capped at 60s.">
        <input
          type="number"
          min={1}
          max={5}
          step={0.5}
          value={value.backoffFactor}
          disabled={disabled}
          onChange={(e) => update('backoffFactor', Number(e.target.value))}
        />
      </SettingField>
    </div>
  );
}
