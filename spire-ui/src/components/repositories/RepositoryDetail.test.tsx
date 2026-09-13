import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import * as api from '../../api';
import RepositoryDetail from './RepositoryDetail';
import type { Repository } from './repositoriesApi';
import type { WebhookRepoView, WebhookEventKind } from '../../api';

const repository: Repository = {
  id: 'TEST-repository', scmType: 'gitlab', forgeOrigin: 'https://forge.example.test', workspace: 'TEST-group/nested',
  slug: 'service', enabled: true, revision: 1,
  reviewer: { id: 'TEST-reviewer', name: 'Review account', role: 'REVIEWER', handle: 'review-bot', state: 'ok' },
  factory: { id: 'TEST-factory', name: 'Push account', role: 'FACTORY', handle: 'push-bot', state: 'disabled' },
};
const hooks: WebhookRepoView[] = (['REVIEWER', 'FACTORY', 'ISSUE'] as WebhookEventKind[]).map(kind => ({
  id: `TEST-${kind}`, providerType: 'gitlab', repositoryId: repository.id, eventKind: kind,
  forgeOrigin: repository.forgeOrigin, scope: 'repo', target: 'TEST-group/nested/service', webhookKey: `TEST-key-${kind}`,
  hasSecret: true, enabled: true, createdAt: '2026-09-13T00:00:00Z',
}));

describe('RepositoryDetail', () => {
  it('links a known-origin legacy hook while preserving its key and secret', async () => {
    const legacy = { ...hooks[0], repositoryId: null };
    const update = vi.spyOn(api, 'updateWebhookRepo').mockResolvedValue(hooks[0]);
    const changed = vi.fn();
    render(<RepositoryDetail repository={repository} hooks={[legacy]} onHooksChanged={changed} onEdit={vi.fn()} />);
    expect(screen.queryByRole('button', { name: 'Create REVIEWER webhook' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Link existing REVIEWER webhook' }));
    await waitFor(() => expect(changed).toHaveBeenCalledWith([hooks[0]]));
    expect(update).toHaveBeenCalledWith(legacy.id, expect.objectContaining({ repositoryId: repository.id, forgeOrigin: repository.forgeOrigin, eventKind: 'REVIEWER' }));
    expect(update.mock.calls[0][1]).not.toHaveProperty('secret');
  });

  it('requires explicit origin repair before creating beside an unresolved legacy hook', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([{ ...hooks[0], repositoryId: null, forgeOrigin: null }]);
    const create = vi.spyOn(api, 'createWebhookRepo');
    render(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={vi.fn()} onEdit={vi.fn()} />);
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
    render(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={changed} onEdit={vi.fn()} />);
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
    render(<RepositoryDetail repository={repository} hooks={[]} onHooksChanged={vi.fn()} onEdit={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Verify reviewer access' }));
    expect(await screen.findByRole('status')).toHaveTextContent('TEST-token cannot see repository');
    expect(verify).toHaveBeenCalledWith('TEST-reviewer', 'TEST-group/nested/service');
  });

  it('shows workspace selected accounts and one webhook per event kind', () => {
    render(<RepositoryDetail repository={repository} hooks={hooks} onHooksChanged={vi.fn()} onEdit={vi.fn()} />);
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
    render(<RepositoryDetail repository={{ ...repository, reviewer: null, factory: null }} hooks={[]} onHooksChanged={vi.fn()} onEdit={vi.fn()} />);
    expect(screen.getByText('Reviewer: No account selected')).toBeInTheDocument();
    expect(screen.getByText('Factory: No account selected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create REVIEWER webhook' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Create FACTORY webhook' })).toBeEnabled();
  });
});
