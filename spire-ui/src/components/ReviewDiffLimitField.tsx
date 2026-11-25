import type { ReviewDiffLimit } from '../api';
import SettingField from './SettingField';

export const REVIEW_DIFF_LIMIT_MIN = 24_000;
export const REVIEW_DIFF_LIMIT_MAX = 2_000_000;

/** Blank when unset, so "as much as the model allows" round-trips as blank rather than as a number. */
export function diffLimitText(limit: ReviewDiffLimit): string {
  return limit.limitTokens == null ? '' : String(limit.limitTokens);
}

/** A blank field is valid (no limit); anything else must be a whole number in the server's range. */
export function validateDiffLimit(text: string): string | null {
  const raw = text.trim();
  if (raw === '') return null;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < REVIEW_DIFF_LIMIT_MIN || parsed > REVIEW_DIFF_LIMIT_MAX) {
    return `Review diff limit must be a whole number from ${REVIEW_DIFF_LIMIT_MIN.toLocaleString('en-US')} to ${REVIEW_DIFF_LIMIT_MAX.toLocaleString('en-US')}, or left blank for as much as the model allows.`;
  }
  return null;
}

/** Only call once {@link validateDiffLimit} has returned `null`. A blank field sends `null`, never `0`. */
export function diffLimitPayload(text: string): ReviewDiffLimit {
  const raw = text.trim();
  return { limitTokens: raw === '' ? null : Number(raw) };
}

interface Props {
  value: string;
  disabled?: boolean;
  onChange: (value: string) => void;
}

/**
 * The Review diff limit: the most diff, in estimated tokens, an API review sends to the model. Blank sends the whole
 * diff whenever the model's context window can hold it. Controlled: {@link SettingsGeneral} owns the state and Save.
 */
export default function ReviewDiffLimitField({ value, disabled, onChange }: Props) {
  return (
    <div className="settings-fields">
      <SettingField
        label="Review diff limit (tokens)"
        scope="code review"
        hint={
          'Blank sends the whole diff whenever the review model’s context window can hold it. A number caps the ' +
          'diff of every API review (24,000 to 2,000,000); files past it are skipped and named in the review. A ' +
          'larger input costs more, and the fleet spend caps still apply. Long-context price tiers (for example ' +
          'OpenAI above 272K input tokens) are not modelled, so such calls are charged at the lower rate: set about ' +
          '90,000 to stay below them.'
        }
      >
        <input
          type="number"
          min={REVIEW_DIFF_LIMIT_MIN}
          max={REVIEW_DIFF_LIMIT_MAX}
          step={1000}
          placeholder="As much as the model allows"
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      </SettingField>
    </div>
  );
}
