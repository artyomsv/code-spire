import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import WebhookSecretReveal from './WebhookSecretReveal';
import type { WebhookRepoSecret, WebhookEventKind } from '../../api';

function revealed(eventKind: WebhookEventKind, providerType = 'github'): WebhookRepoSecret {
  return { secret: 'TEST-secret', repo: { id: 'TEST-hook', repositoryId: 'TEST-repo', eventKind, sourceId: null, forgeOrigin: 'https://github.com',
    providerType, scope: 'repo', target: 'TEST-owner/TEST-repo', webhookKey: `TEST-key-${eventKind}`, hasSecret: true, enabled: true, createdAt: '1970-01-01T00:00:00Z' } };
}

describe('WebhookSecretReveal', () => {
  it('shows the portal steps with the events of the created kind', () => {
    render(<WebhookSecretReveal revealed={revealed('ISSUE')} onDone={vi.fn()} />);
    const dialog = screen.getByRole('dialog', { name: 'Webhook secret' });
    expect(within(dialog).getByText('Next — set up on GitHub')).toBeInTheDocument();
    expect(within(dialog).getByText(/Open your repo → Settings → Webhooks → Add webhook/)).toBeInTheDocument();
    expect(within(dialog).getByText('Issues')).toBeInTheDocument();
    expect(within(dialog).getByText('Issue comments')).toBeInTheDocument();
    expect(within(dialog).queryByText('Pull requests')).toBeNull();
  });

  it('lists pushes for a factory hook and still shows the secret and the path once', () => {
    render(<WebhookSecretReveal revealed={revealed('FACTORY', 'gitlab')} onDone={vi.fn()} />);
    const dialog = screen.getByRole('dialog', { name: 'Webhook secret' });
    expect(within(dialog).getByText('Next — set up on GitLab')).toBeInTheDocument();
    expect(within(dialog).getByText('Push events')).toBeInTheDocument();
    expect(within(dialog).getByText('TEST-secret')).toBeInTheDocument();
    expect(within(dialog).getByText('/webhooks/gitlab/TEST-key-FACTORY')).toBeInTheDocument();
  });

  it('after a rotation tells the operator to replace the secret on an existing webhook, and keeps the steps for one not created yet', () => {
    render(<WebhookSecretReveal revealed={revealed('ISSUE')} rotated onDone={vi.fn()} />);
    const dialog = screen.getByRole('dialog', { name: 'Webhook secret' });
    expect(within(dialog).getByText(/webhook already exists on GitHub/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/not created it yet/i)).toBeInTheDocument();
    expect(within(dialog).getByText('Add webhook')).toBeInTheDocument();
    expect(within(dialog).getByText('Issues')).toBeInTheDocument();
    expect(within(dialog).getByText('TEST-secret')).toBeInTheDocument();
  });

  it('names the path the way the steps refer to it', () => {
    render(<WebhookSecretReveal revealed={revealed('REVIEWER')} onDone={vi.fn()} />);
    expect(screen.getByText('Payload URL (path)')).toBeInTheDocument();
    expect(screen.getByText(/Paste the Payload URL above/)).toBeInTheDocument();
  });

  it('shows no steps for a forge without a guide', () => {
    render(<WebhookSecretReveal revealed={revealed('REVIEWER', 'bitbucket-dc')} onDone={vi.fn()} />);
    expect(screen.queryByText(/Next — set up on/)).toBeNull();
  });
});
