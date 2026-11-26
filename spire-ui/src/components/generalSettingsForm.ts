import {
  setCapSettings,
  setConversationSettings,
  setReviewSettings,
  type ConversationSettings,
  type ReviewSettings,
} from '../api';
import { setReviewDiffLimit } from '../reviewDiffLimitApi';
import { capFieldsPayload, initialCapFields, validateCapFields, type CapFieldValues } from './CapSettings';
import { diffLimitPayload, diffLimitText, validateDiffLimit } from './ReviewDiffLimitField';

/** Every group the General page edits, as the form holds it; also what the form was filled from. */
export interface GeneralForm {
  review: ReviewSettings;
  conversation: ConversationSettings;
  caps: CapFieldValues;
  /** Kept as text, as the cap fields are: a blank field sends null ("as much as the model allows"), never 0. */
  diffLimit: string;
}

/**
 * The first field error, or null. Checked before anything is sent: a typed 0 is rejected outright, not just skipped,
 * and rejecting it means the OTHER groups are not saved either on the same click — one Save for the whole page.
 */
export function validateGeneral(form: GeneralForm): string | null {
  return validateDiffLimit(form.diffLimit) ?? validateCapFields(form.caps);
}

/**
 * Writes only the groups that moved since `loaded` (an untouched group is not rewritten because its sibling was) and
 * returns what the server stored, since it clamps out-of-range values.
 */
export async function saveChangedGroups(form: GeneralForm, loaded: GeneralForm): Promise<GeneralForm> {
  const [review, conversation, caps, diffLimit] = await Promise.all([
    form.review.maxAttempts !== loaded.review.maxAttempts ? setReviewSettings(form.review) : Promise.resolve(loaded.review),
    differs(form.conversation, loaded.conversation)
      ? setConversationSettings(form.conversation)
      : Promise.resolve(loaded.conversation),
    differs(form.caps, loaded.caps)
      ? setCapSettings(capFieldsPayload(form.caps)).then(initialCapFields)
      : Promise.resolve(loaded.caps),
    form.diffLimit.trim() !== loaded.diffLimit
      ? setReviewDiffLimit(diffLimitPayload(form.diffLimit)).then(diffLimitText)
      : Promise.resolve(loaded.diffLimit),
  ]);
  return { review, conversation, caps, diffLimit };
}

function differs(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) !== JSON.stringify(b);
}
