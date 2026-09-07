import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsContextProviders from './SettingsContextProviders';
import * as api from '../api';

const source: api.ContextProviderView = {
  id: 'TEST-c1',
  name: 'TEST Jira',
  type: 'jira',
  baseUrl: 'https://test-acme.atlassian.net',
  authKind: 'basic',
  username: 'jira-bot@example.invalid',
  projectKeys: null,
  hasSecret: true,
  enabled: true,
  isDefault: false,
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

/**
 * Every context source is read by exactly one consumer today, the reviewer's context aggregator.
 * The column says so, and becomes data when M3 registers a write-capable account on the same host.
 */
describe('SettingsContextProviders — Used by', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([source, { ...source, id: 'TEST-c2', name: 'TEST Wiki', type: 'confluence' }]);
    vi.spyOn(api, 'checkContextProvider').mockResolvedValue({ ok: true, account: 'jira-bot', detail: null } as never);
  });

  it('shows Reviewer · read on every row', async () => {
    render(<MemoryRouter><SettingsContextProviders /></MemoryRouter>);
    expect(await screen.findAllByText('Reviewer · read')).toHaveLength(2);
    expect(screen.getByRole('columnheader', { name: 'Used by' })).toBeInTheDocument();
  });
});
