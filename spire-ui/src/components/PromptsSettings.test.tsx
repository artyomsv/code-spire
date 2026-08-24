import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import PromptsSettings from './PromptsSettings';
import PromptDetail from './PromptDetail';
import * as api from '../api';

const reviewView: api.PromptView = {
  kind: 'review',
  scope: '*',
  inheritedFrom: 'default',
  customized: false,
  system: 'persona',
  body: 'review {{diff}}',
  updatedAt: null,
  palette: [{ name: 'diff', required: true, fenced: true, maxTokens: 24000, description: 'The diff.' }],
  lockedSuffixPreview: 'SECURITY: ... "findings"',
  baseKnown: true,
  defaultDrifted: false,
  currentDefaultSystem: 'persona',
  currentDefaultBody: 'review {{diff}}',
  baseSystem: null,
  baseBody: null,
};
const reconcileView: api.PromptView = {
  ...reviewView,
  kind: 'reconcile',
  inheritedFrom: 'global',
  customized: true,
  baseSystem: 'persona',
  baseBody: 'review {{diff}}',
};
const followupView: api.PromptView = { ...reviewView, kind: 'followup' };

describe('PromptsSettings (list)', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([reviewView, reconcileView, followupView]);
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue([]);
  });

  it('lists each prompt kind as a link, tagged Default/Custom', async () => {
    render(
      <MemoryRouter>
        <PromptsSettings />
      </MemoryRouter>,
    );
    // One clickable row per kind. Names are anchored at the start (the label leads the button's
    // accessible name, which concatenates without a space as "ReviewDefault…") so a "Re-review"
    // in a blurb can't collide.
    await waitFor(() => expect(screen.getByRole('button', { name: /^Review/ })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /^Reconcile/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Follow-up/ })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(3);
    // The customized kind is tagged Custom; the list no longer shows editors/palettes.
    expect(screen.getByText('Custom')).toBeInTheDocument();
  });

  it('badges only the kind whose shipped default has drifted', async () => {
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([
      reviewView,
      { ...reconcileView, defaultDrifted: true },
      followupView,
    ]);
    render(
      <MemoryRouter>
        <PromptsSettings />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/update available/i)).toBeInTheDocument());
    expect(screen.getAllByText(/update available/i)).toHaveLength(1);
  });

  it('distinguishes a repo override from an inherited global at a repository scope', async () => {
    // The exact ambiguity this task exists to remove: at a repo scope, "Custom" alone cannot say
    // whether THIS repo was customized or the row is falling back to the global override.
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([
      { ...reviewView, scope: 'acme/widgets', inheritedFrom: 'repo', customized: true },
      { ...reconcileView, scope: 'acme/widgets', inheritedFrom: 'global', customized: true },
      { ...followupView, scope: 'acme/widgets', inheritedFrom: 'default', customized: false },
    ]);
    render(
      <MemoryRouter initialEntries={['/settings/prompts?scope=acme/widgets']}>
        <PromptsSettings />
      </MemoryRouter>,
    );

    await waitFor(() => expect(api.fetchPrompts).toHaveBeenCalledWith('acme/widgets'));
    expect(screen.getByText('Custom · this repo')).toBeInTheDocument();
    expect(screen.getByText('Inherited · global')).toBeInTheDocument();
    expect(screen.getByText('Default')).toBeInTheDocument();
  });

  it('carries the URL scope into a kind link so a reload lands back on the same repository', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(reviewView);
    render(
      <MemoryRouter initialEntries={['/settings/prompts?scope=acme/widgets']}>
        <Routes>
          <Route path="/settings/prompts" element={<PromptsSettings />} />
          <Route path="/settings/prompts/:kind" element={<PromptDetail />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('button', { name: /^Review/ })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /^Review/ }));

    await waitFor(() => expect(api.fetchPrompt).toHaveBeenCalledWith('review', 'acme/widgets'));
  });

  it('re-fetches at the new scope when the picker changes', async () => {
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue(['acme/widgets']);
    render(
      <MemoryRouter initialEntries={['/settings/prompts']}>
        <PromptsSettings />
      </MemoryRouter>,
    );

    await waitFor(() => expect(api.fetchPrompts).toHaveBeenCalledWith('*'));
    fireEvent.change(await screen.findByLabelText('Prompt scope'), { target: { value: 'acme/widgets' } });

    await waitFor(() => expect(api.fetchPrompts).toHaveBeenCalledWith('acme/widgets'));
  });
});

describe('PromptDetail (edit)', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(reviewView);
  });

  it('loads the kind from the route and renders its variable palette', async () => {
    render(
      <MemoryRouter initialEntries={['/settings/prompts/review']}>
        <Routes>
          <Route path="/settings/prompts/:kind" element={<PromptDetail />} />
        </Routes>
      </MemoryRouter>,
    );
    // The editor heading + the palette chip live on the detail page now.
    await waitFor(() => expect(screen.getByRole('heading', { name: /review/i })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /\{\{diff\}\}/ })).toBeInTheDocument();
    expect(api.fetchPrompt).toHaveBeenCalledWith('review', '*');
  });
});
