import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as accounts from '../../api';
import * as api from './repositoriesApi';
import RepositorySwitches from './RepositorySwitches';
import RepositorySettings from './RepositorySettings';
import { queryWrapper } from '../../test/queryWrapper';

const HINT = 'Sets review: approved or review: changes-requested after each review and removes other review: labels. With Start review after checks finish, a red build sets review: ci-failed.';

const fields = (scmType: string): api.RepositoryInput => ({
  scmType, forgeOrigin: 'https://forge.example.test', workspace: 'TEST-owner', slug: 'TEST-repo', enabled: true,
  reviewerAccountId: null, factoryAccountId: null, reviewerMayApprove: true, blockApprovalAt: 'BLOCKER', verdictLabels: false,
  reviewLlmProviderId: null, reviewLlmModel: null, reviewHarness: null,
});

const github: api.Repository = {
  id: 'TEST-repo-id', scmType: 'github', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-owner',
  slug: 'TEST-repo', enabled: true, revision: 3, reviewer: null, factory: null, reviewerMayApprove: true,
  blockApprovalAt: 'BLOCKER', verdictLabels: false, reviewLlmProviderId: null, reviewLlmModel: null, reviewHarness: null,
};

/** V120 "Verdict labels": a GitHub-only repository setting, off by default. */
describe('Verdict labels', () => {
  beforeEach(() => {
    vi.spyOn(accounts, 'fetchLlmProviders').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchLlmDefaultModel').mockResolvedValue({ provider: null, model: null });
    vi.spyOn(api, 'saveRepository').mockResolvedValue({ ...github, verdictLabels: true, revision: 4 });
  });

  it('is offered on a GitHub repository with its hint, and a click turns it on', () => {
    const onChange = vi.fn();
    render(<RepositorySwitches fields={fields('github')} onChange={onChange} />);

    const setting = screen.getByLabelText('Verdict labels');
    expect(setting).not.toBeChecked();
    expect(screen.getByText(HINT)).toBeInTheDocument();
    fireEvent.click(setting);
    expect(onChange).toHaveBeenCalledWith({ verdictLabels: true });
  });

  it.each(['gitlab', 'bitbucket'])('is hidden on a %s repository, which has no label call', scmType => {
    render(<RepositorySwitches fields={fields(scmType)} onChange={vi.fn()} />);

    expect(screen.queryByLabelText('Verdict labels')).not.toBeInTheDocument();
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
  });

  it('is saved from the Settings tab', async () => {
    render(<RepositorySettings repository={github} providers={[]} hidden={false} onSaved={vi.fn()} onState={vi.fn()} />,
      { wrapper: queryWrapper() });

    fireEvent.click(screen.getByLabelText('Verdict labels'));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(
      expect.objectContaining({ verdictLabels: true }), github));
  });
});
