import { throwResponse } from './api';
import { apiFetch } from './auth';

// The Review diff limit: the most diff an API review sends to the model (Settings -> General). Its own module rather
// than another section of api.ts.

/** `null` means "as much as the model's window allows"; a number caps the diff, from 24,000 to 2,000,000 tokens. */
export interface ReviewDiffLimit {
  limitTokens: number | null;
}

/**
 * What the server reports: the limit, and whether a stored row is not a valid limit (`limitTokens` is then null and
 * reviews use the lowest limit until it is saved again).
 */
export interface StoredReviewDiffLimit extends ReviewDiffLimit {
  invalidStoredValue?: boolean;
}

export async function getReviewDiffLimit(): Promise<StoredReviewDiffLimit> {
  const res = await apiFetch('/api/settings/review-diff-limit');
  if (!res.ok) return throwResponse(res, 'Failed to load the review diff limit');
  return res.json();
}

export async function setReviewDiffLimit(limit: ReviewDiffLimit): Promise<StoredReviewDiffLimit> {
  const res = await apiFetch('/api/settings/review-diff-limit', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(limit),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update the review diff limit');
  return res.json();
}
