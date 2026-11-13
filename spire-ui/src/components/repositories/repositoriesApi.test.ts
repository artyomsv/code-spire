import { beforeEach, expect, it, vi } from 'vitest';
import { apiFetch } from '../../auth';
import { saveRepository, type RepositoryInput } from './repositoriesApi';
vi.mock('../../auth', () => ({ apiFetch: vi.fn() }));

const input = { scmType: 'github', forgeOrigin: 'https://TEST.invalid', workspace: 'TEST-ws', slug: 'TEST-repo' } as RepositoryInput;
beforeEach(() => vi.mocked(apiFetch).mockReset());

// A refusal says why in its body (harness reviews 3b): the screen shows that, not a generic failure.
it('shows the reason a refused save gives', async () => {
  vi.mocked(apiFetch).mockResolvedValue(new Response('Review with: subscription_not_allowed — the claude_code harness pays with an API key only', { status: 400 }));
  await expect(saveRepository(input, null)).rejects.toThrow('Review with: subscription_not_allowed — the claude_code harness pays with an API key only');
});

it('falls back to a generic message when a refusal has no body', async () => {
  vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status: 400 }));
  await expect(saveRepository(input, null)).rejects.toThrow('Could not save repository');
});
