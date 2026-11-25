import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import * as accounts from '../../api';
import * as api from './repositoriesApi';
import RepositorySwitches from './RepositorySwitches';
import RepositorySettings from './RepositorySettings';
import { START_REVIEW_HINT } from './RepositoryStartReview';
import { queryWrapper } from '../../test/queryWrapper';

const fields = (scmType: string): api.RepositoryInput => ({
  scmType, forgeOrigin: 'https://forge.example.test', workspace: 'TEST-owner', slug: 'TEST-repo', enabled: true,
  reviewerAccountId: null, factoryAccountId: null, reviewerMayApprove: true, blockApprovalAt: 'BLOCKER', verdictLabels: false,
  startReview: 'IMMEDIATELY', reviewLlmProviderId: null, reviewLlmModel: null, reviewHarness: null,
});

const github: api.Repository = {
  id: 'TEST-repo-id', scmType: 'github', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-owner',
  slug: 'TEST-repo', enabled: true, revision: 3, reviewer: null, factory: null, reviewerMayApprove: true,
  blockApprovalAt: 'BLOCKER', verdictLabels: false, reviewLlmProviderId: null, reviewLlmModel: null, reviewHarness: null,
};

/** V121 "Start review": a GitHub-only repository setting beside "Verdict labels", immediately by default. */
describe('Start review', () => {
  beforeEach(() => {
    vi.spyOn(accounts, 'fetchLlmProviders').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(accounts, 'fetchLlmDefaultModel').mockResolvedValue({ provider: null, model: null });
    vi.spyOn(api, 'saveRepository').mockResolvedValue({ ...github, startReview: 'AFTER_CHECKS', revision: 4 });
  });

  it('is offered on a GitHub repository, immediately by default, explained on its info control, and a choice changes it', () => {
    const onChange = vi.fn();
    render(<RepositorySwitches fields={fields('github')} onChange={onChange} />);

    const setting = screen.getByRole('combobox', { name: 'Start review' });
    expect(setting).toHaveValue('IMMEDIATELY');
    fireEvent.focus(screen.getByRole('button', { name: 'About start review — repository' }));
    expect(screen.getByRole('tooltip')).toHaveTextContent(START_REVIEW_HINT);
    expect(screen.queryByText(/a red build gets no review/, { selector: 'p' })).not.toBeInTheDocument();
    fireEvent.change(setting, { target: { value: 'AFTER_CHECKS' } });
    expect(onChange).toHaveBeenCalledWith({ startReview: 'AFTER_CHECKS' });
  });

  it.each(['gitlab', 'bitbucket'])('is hidden on a %s repository, which has no check read', scmType => {
    render(<RepositorySwitches fields={fields(scmType)} onChange={vi.fn()} />);

    expect(screen.queryByRole('combobox', { name: 'Start review' })).not.toBeInTheDocument();
  });

  it('is saved from the Settings tab; a repository from an older server reads as immediately', async () => {
    render(<RepositorySettings repository={github} providers={[]} hidden={false} onSaved={vi.fn()} onState={vi.fn()} />,
      { wrapper: queryWrapper() });

    expect(screen.getByRole('combobox', { name: 'Start review' })).toHaveValue('IMMEDIATELY');
    fireEvent.change(screen.getByRole('combobox', { name: 'Start review' }), { target: { value: 'AFTER_CHECKS' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(api.saveRepository).toHaveBeenCalledWith(
      expect.objectContaining({ startReview: 'AFTER_CHECKS' }), github));
  });
});
