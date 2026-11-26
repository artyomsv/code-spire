import { throwResponse } from './api';
import { apiFetch } from './auth';

// The Review diff limit: the most diff an API review sends to the model (Settings -> General). Its own module rather
// than another section of api.ts.

/** `null` means "as much as the model's window allows"; a number caps the diff, from 24,000 to 2,000,000 tokens. */
export interface ReviewDiffLimit {
  limitTokens: number | null;
}

export async function getReviewDiffLimit(): Promise<ReviewDiffLimit> {
  const res = await apiFetch('/api/settings/review-diff-limit');
  if (!res.ok) return throwResponse(res, 'Failed to load the review diff limit');
  return res.json();
}

export async function setReviewDiffLimit(limit: ReviewDiffLimit): Promise<ReviewDiffLimit> {
  const res = await apiFetch('/api/settings/review-diff-limit', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(limit),
  });
  if (!res.ok) return throwResponse(res, 'Failed to update the review diff limit');
  return res.json();
}
