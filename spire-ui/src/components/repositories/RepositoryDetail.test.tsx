vi.mock('../actorsApi', async importOriginal => ({ ...await importOriginal<typeof import('../actorsApi')>(), fetchActors: vi.fn().mockResolvedValue({ revision: 0, actors: [] }) }));
import { describe, it, expect, vi } from 'vitest';
import { screen, within, fireEvent, waitFor } from '@testing-library/react';
import * as api from '../../api';
import RepositoryDetail from './RepositoryDetail';
import type { Repository } from './repositoriesApi';
import type { WebhookRepoView, WebhookEventKind } from '../../api';
import { renderWithQuery } from '../../test/queryWrapper';

const repository: Repository = {
  id: 'TEST-repository', scmType: 'gitlab', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-group/nested',
  slug: 'service', enabled: true, revision: 1, reviewerMayApprove: true, reviewLlmProviderId: null,
  reviewer: { id: 'TEST-reviewer', name: 'Review account', role: 'REVIEWER', handle: 'review-bot', state: 'ok' },
  factory: { id: 'TEST-factory', name: 'Push account', role: 'FACTORY', handle: 'push-bot', state: 'disabled' },
};
const hooks: WebhookRepoView[] = (['REVIEWER', 'FACTORY', 'ISSUE'] as WebhookEventKind[]).map(kind => ({
  id: `TEST-${kind}`, providerType: 'gitlab', repositoryId: repository.id, eventKind: kind,
  forgeOrigin: repository.forgeOrigin, scope: 'repo', target: 'TEST-group/nested/service', webhookKey: `TEST-key-${kind}`,
  hasSecret: true, enabled: true, createdAt: '2026-09-13T00:00:00Z',
}));

describe('RepositoryDetail', () => {
  it('preserves the webhook path heading truncation and vertical account badges', () => {
    renderWithQuery(<RepositoryDetail repository={repository} hooks={hooks} onHooksChanged={vi.fn()} />);
    expect(screen.getByRole('columnheader', { name: 'Webhook path' })).toBeInTheDocument();
    const path = screen.getByText('/webhooks/gitlab/TEST-key-REVIEWER');
    expect(path.closest('.wh-url')).not.toBeNull();
    expect(path).toHaveAttribute('title', '/webhooks/gitlab/TEST-key-REVIEWER');
    const accounts = screen.getByRole('region', { name: 'Selected accounts' });
    expect(accounts.querySelector('.serving-pair')).toContainElement(within(accounts).getByText('Reviewer: Review account (@review-bot) · ok'));
    expect(accounts.querySelector('.serving-pair')).toContainElement(within(accounts).getByText('Factory: Push account (@push-bot) · disabled'));
    expect(within(accounts).getByText('Reviewer may approve: on')).toBeInTheDocument();
  });

  it('links a known-origin legacy hook while preserving its key and secret', async () => {
    const legacy = { ...hooks[0], repositoryId: null };
    const update = vi.spyOn(api, 'updateWebhookRepo').mockResolvedValue(hooks[0]);
    const changed = vi.fn();
    renderWithQuery(<RepositoryDetail repository={repository} hooks={[legacy]} onHooksChanged={changed} />);
    expect(screen.queryByRole('button', { name: 'Create REVIEWER webhook' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Link existing REVIEWER webhook' }));
    await waitFor(() => expect(changed).toHaveBeenCalledWith([hooks[0]]));
    expect(update).toHaveBeenCalledWith(legacy.id, expect.objectContaining({ repositoryId: repository.id, forgeOrigin: repository.forgeOrigin, eventKind: 'REVIEWER' }));
    expect(update.mock.calls[0][1]).not.toHaveProperty('secret');
  });

  it('requires explicit origin repair before creating beside an unresolved legacy hook', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([{ ...hooks[0], repositoryId: null, forgeOrigin: null }]);
    const create = vi.spyOn(api, 'createWebhookRepo');
    renderWithQuery(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create REVIEWER webhook' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('forge origin confirmed');
    expect(create).not.toHaveBeenCalled();
  });
  it('recovers a lost webhook response without creating a duplicate', async () => {
    const saved = hooks[0];
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValueOnce([]).mockResolvedValueOnce([saved]);
    const create = vi.spyOn(api, 'createWebhookRepo').mockRejectedValue(new Error('TEST-response lost'));
    const rotate = vi.spyOn(api, 'rotateWebhookSecret');
    const changed = vi.fn();
    renderWithQuery(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={changed} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create REVIEWER webhook' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Repository remains saved');
    fireEvent.click(screen.getByRole('button', { name: 'Create REVIEWER webhook' }));
    await waitFor(() => expect(changed).toHaveBeenCalledWith([saved]));
    expect(create).toHaveBeenCalledTimes(1);
    expect(rotate).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('rotate it explicitly');
  });

  it('verifies the full namespace with the selected reviewer and names a failure', async () => {
    const verify = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: false, detail: 'TEST-token cannot see repository' });
    renderWithQuery(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Verify reviewer access' }));
    expect(await screen.findByRole('status')).toHaveTextContent('TEST-token cannot see repository');
    expect(verify).toHaveBeenCalledWith('TEST-reviewer', 'TEST-group/nested/service');
  });

  it('shows workspace selected accounts and one webhook per event kind', () => {
    renderWithQuery(<RepositoryDetail repository={repository} hooks={hooks} onHooksChanged={vi.fn()} />);
    expect(within(screen.getByRole('region', { name: 'Workspace' })).getByText('TEST-group/nested')).toBeInTheDocument();
    const accounts = within(screen.getByRole('region', { name: 'Selected accounts' }));
    expect(accounts.getByText('Reviewer: Review account (@review-bot) · ok')).toBeInTheDocument();
    expect(accounts.getByText('Factory: Push account (@push-bot) · disabled')).toBeInTheDocument();
    const webhookSection = within(screen.getByRole('region', { name: 'Webhooks' }));
    for (const kind of ['REVIEWER', 'FACTORY', 'ISSUE']) {
      const groups = webhookSection.getAllByRole('group', { name: `${kind} webhook` });
      expect(groups).toHaveLength(1);
      expect(within(groups[0]).getByText(`/webhooks/gitlab/TEST-key-${kind}`)).toBeInTheDocument();
    }
  });

  it('shows missing role bindings and permits a repository with no hooks', () => {
    renderWithQuery(<RepositoryDetail repository={{ ...repository, reviewer: null, factory: null }} hooks={[]} onHooksChanged={vi.fn()} />);
    expect(screen.getByText('Reviewer: No account selected')).toBeInTheDocument();
    expect(screen.getByText('Factory: No account selected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create REVIEWER webhook' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Create FACTORY webhook' })).toBeEnabled();
  });
});

describe('RepositoryDetail secret rotation', () => {
  it('tells the operator to replace the secret on the existing forge webhook, not to add a second one', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(api, 'rotateWebhookSecret').mockResolvedValue({ repo: hooks[1], secret: 'TEST-rotated-secret' });
    renderWithQuery(<RepositoryDetail repository={repository} hooks={hooks} onHooksChanged={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Rotate FACTORY secret' }));
    const dialog = await screen.findByRole('dialog', { name: 'Webhook secret' });
    expect(within(dialog).getByText('TEST-rotated-secret')).toBeInTheDocument();
    expect(within(dialog).getByText(/webhook already exists on GitLab/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/Add new webhook/)).toBeInTheDocument();
    expect(within(dialog).getByText('Push events')).toBeInTheDocument();
  });
});
