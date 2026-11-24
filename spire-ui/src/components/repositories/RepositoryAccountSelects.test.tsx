import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ProviderView } from '../../api';
import type { RepositoryInput } from './repositoriesApi';
import RepositoryAccountSelects from './RepositoryAccountSelects';

const account = (id: string, baseUrl: string, type = 'github'): ProviderView => ({
  id, name: id, role: 'REVIEWER', baseUrl, type, enabled: true,
  authKind: 'bearer', authUsername: null, hasSecret: true, botAccountId: `TEST-id-${id}`, botUsername: id,
  authors: [], conversationLevel: null, createdAt: '', lastCheckAt: null, lastCheckOk: null, lastCheckError: null,
});
const fields = (forgeOrigin: string): RepositoryInput => ({
  scmType: 'github', forgeOrigin, workspace: 'TEST-owner', slug: 'TEST-repo', enabled: true,
  reviewerAccountId: null, factoryAccountId: null, reviewerMayApprove: true, blockApprovalAt: 'BLOCKER',
  reviewLlmProviderId: null, reviewLlmModel: null, reviewHarness: null,
});

describe('RepositoryAccountSelects', () => {
  it('offers the https origin when the typed http one matches no account on the same host', () => {
    const onChange = vi.fn();
    render(<RepositoryAccountSelects fields={fields('http://api.github.com')}
      providers={[account('TEST-mole', 'https://api.github.com')]} onChange={onChange} />);

    expect(screen.getByText(/this forge's accounts are on https:\/\/api\.github\.com/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Use https://api.github.com' }));
    expect(onChange).toHaveBeenCalledWith({ forgeOrigin: 'https://api.github.com', reviewerAccountId: null, factoryAccountId: null });
  });

  it('lists the account and offers no switch when the origin already matches', () => {
    render(<RepositoryAccountSelects fields={fields('https://api.github.com')}
      providers={[account('TEST-mole', 'https://api.github.com')]} onChange={vi.fn()} />);

    expect(screen.getByRole('option', { name: /TEST-mole/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Use / })).not.toBeInTheDocument();
  });

  it('keeps the add-an-account note when no account of this forge is on that host', () => {
    render(<RepositoryAccountSelects fields={fields('http://forge.example.test')}
      providers={[account('TEST-other-host', 'https://api.github.com'), account('TEST-gitlab', 'https://forge.example.test', 'gitlab')]}
      onChange={vi.fn()} />);

    expect(screen.getByText(/No accounts on http:\/\/forge\.example\.test\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add an account' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Use / })).not.toBeInTheDocument();
  });
});
