import { useId } from 'react';
import type { ReviewDiffLimit } from '../reviewDiffLimitApi';
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

/**
 * Matches docs/HISTORY.md and docs/UNVERIFIED.md: the limit caps the diff only, and the rest of the prompt (about 14,000
 * tokens) comes on top, so 60,000 keeps a whole review below the 100,000-token long-context tier with room for the
 * estimate to run low.
 */
const HINT =
  'Blank sends the whole diff whenever the review model’s context window can hold it. A number caps the diff of ' +
  'every API review (24,000 to 2,000,000); files past it are skipped and named in the review. The cap is on the ' +
  'diff only, not on the whole prompt: the instructions, linked context and earlier findings come on top. A larger ' +
  'input costs more, and the fleet spend caps still apply. Long-context price tiers (for example above 100,000 or ' +
  '272,000 input tokens) are not modelled, so such calls are charged at the lower rate: set about 60,000 to keep a ' +
  'whole review below them.';

/** Shown while the stored row is not a valid limit; reviews meanwhile have a 24,000-token diff cap. */
export const INVALID_STORED_MESSAGE = 'The stored value is invalid; save to clear it or enter a new limit';

interface Props {
  value: string;
  disabled?: boolean;
  /** The stored row is not a valid limit, so a Save is sent even when the field stays blank. */
  invalidStored?: boolean;
  onChange: (value: string) => void;
}

/**
 * The Review diff limit: the most diff, in estimated tokens, an API review sends to the model. Blank sends the whole
 * diff whenever the model's context window can hold it. Controlled: {@link SettingsGeneral} owns the state and Save.
 */
export default function ReviewDiffLimitField({ value, disabled, invalidStored, onChange }: Props) {
  const inputId = useId();
  return (
    <div className="settings-fields">
      <SettingField label="Review diff limit (tokens)" scope="code review" hint={HINT} inputId={inputId}>
        <input
          id={inputId}
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
      {invalidStored && <p className="prov-error">{INVALID_STORED_MESSAGE}</p>}
    </div>
  );
}
