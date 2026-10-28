import { expect, it } from 'vitest';
import { createQueryClient } from './queryClient';

it('retries nothing and does not re-read on window focus', () => {
  const defaults = createQueryClient().getDefaultOptions().queries;
  expect(defaults?.retry).toBe(false);
  expect(defaults?.refetchOnWindowFocus).toBe(false);
});
