import type { CapSettings as CapSettingsShape } from '../api';
import SettingField from './SettingField';

/**
 * The five limit fields as raw input strings, never as {@link CapSettingsShape}'s `number | null`
 * directly. Task 9 exists because a blank field must send `null`, not the `0` that `Number('')`
 * produces — keeping the control's own state as text is what lets a blank field stay blank instead
 * of silently becoming a number.
 */
export type CapFieldValues = Record<keyof CapSettingsShape, string>;

const FIELD_LABEL: Record<keyof CapSettingsShape, string> = {
  maxChangedFiles: 'Max changed files',
  maxDiffBytes: 'Max diff bytes',
  spendCapMillicents: 'Spend cap (millicents)',
  callCap: 'Call cap',
  windowMinutes: 'Window (minutes)',
};

/** Blank when unset, so a never-configured limit round-trips as blank rather than as "0". */
export function initialCapFields(current: CapSettingsShape): CapFieldValues {
  return {
    maxChangedFiles: toText(current.maxChangedFiles),
    maxDiffBytes: toText(current.maxDiffBytes),
    spendCapMillicents: toText(current.spendCapMillicents),
    callCap: toText(current.callCap),
    windowMinutes: toText(current.windowMinutes),
  };
}

/**
 * A blank field is always valid — unlimited. A stored `0` is indistinguishable from "unset" once it
 * reaches the wire, so it is refused here rather than silently sent as the cap it is not: `0` would
 * mean "refuse every review", the opposite of what a blank field means. Same rule for a negative or
 * fractional value — none of these five limits has a fractional unit.
 */
export function validateCapFields(v: CapFieldValues): string | null {
  for (const key of Object.keys(v) as (keyof CapFieldValues)[]) {
    const raw = v[key].trim();
    if (raw === '') continue;
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      return `${FIELD_LABEL[key]} must be a positive whole number, or left blank for unlimited.`;
    }
  }
  return null;
}

/** Only call once {@link validateCapFields} has returned `null` for `v`. */
export function capFieldsPayload(v: CapFieldValues): CapSettingsShape {
  return {
    maxChangedFiles: toNumber(v.maxChangedFiles),
    maxDiffBytes: toNumber(v.maxDiffBytes),
    spendCapMillicents: toNumber(v.spendCapMillicents),
    callCap: toNumber(v.callCap),
    windowMinutes: toNumber(v.windowMinutes),
  };
}

function toText(value: number | null): string {
  return value == null ? '' : String(value);
}

function toNumber(raw: string): number | null {
  const trimmed = raw.trim();
  return trimmed === '' ? null : Number(trimmed);
}

interface Props {
  value: CapFieldValues;
  disabled?: boolean;
  onChange: (value: CapFieldValues) => void;
}

/**
 * The fleet-wide spend limits: refuse a diff too large to be worth reviewing, and refuse to spend
 * further once a review or a follow-up would exceed the money or call budget for the window. Every
 * field is optional and blank means unlimited — see {@link validateCapFields} for why a typed `0` is
 * rejected rather than treated the same way.
 *
 * Controlled: {@link SettingsGeneral} owns the state and the single Save.
 */
export default function CapSettings({ value, disabled, onChange }: Props) {
  function update(key: keyof CapFieldValues, raw: string) {
    onChange({ ...value, [key]: raw });
  }

  return (
    <div className="settings-fields">
      <SettingField
        scope="limits"
        label={FIELD_LABEL.maxChangedFiles}
        hint="Refuse a diff that touches more files than this. Blank means no limit."
      >
        <input
          type="number"
          min={1}
          step={1}
          placeholder="unlimited"
          value={value.maxChangedFiles}
          disabled={disabled}
          onChange={(e) => update('maxChangedFiles', e.target.value)}
        />
      </SettingField>

      <SettingField
        scope="limits"
        label={FIELD_LABEL.maxDiffBytes}
        hint="Refuse a diff larger than this many bytes. Blank means no limit."
      >
        <input
          type="number"
          min={1}
          step={1}
          placeholder="unlimited"
          value={value.maxDiffBytes}
          disabled={disabled}
          onChange={(e) => update('maxDiffBytes', e.target.value)}
        />
      </SettingField>

      <SettingField
        scope="limits"
        label={FIELD_LABEL.spendCapMillicents}
        hint={
          'Refuse further reviews once total spend in the window reaches this amount. Money is ' +
          'stored and shown in millicents ($1.00 = 100,000 millicents). Blank means no limit.'
        }
      >
        <input
          type="number"
          min={1}
          step={1}
          placeholder="unlimited"
          value={value.spendCapMillicents}
          disabled={disabled}
          onChange={(e) => update('spendCapMillicents', e.target.value)}
        />
      </SettingField>

      <SettingField
        scope="limits"
        label={FIELD_LABEL.callCap}
        hint={
          'Refuse further reviews once total LLM calls in the window reaches this many — the axis ' +
          'that still bites on an unmetered deployment, where every charge is $0. Blank means no limit.'
        }
      >
        <input
          type="number"
          min={1}
          step={1}
          placeholder="unlimited"
          value={value.callCap}
          disabled={disabled}
          onChange={(e) => update('callCap', e.target.value)}
        />
      </SettingField>

      <SettingField
        scope="limits"
        label={FIELD_LABEL.windowMinutes}
        hint="The rolling window the limits above apply to. Blank uses the default, 1440 minutes (one day)."
      >
        <input
          type="number"
          min={1}
          step={1}
          placeholder="1440"
          value={value.windowMinutes}
          disabled={disabled}
          onChange={(e) => update('windowMinutes', e.target.value)}
        />
      </SettingField>
    </div>
  );
}
