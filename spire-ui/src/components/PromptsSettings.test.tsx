import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PromptsSettings from './PromptsSettings';
import PromptDetail from './PromptDetail';
import * as api from '../api';

const reviewView: api.PromptView = {
  kind: 'review',
  customized: false,
  system: 'persona',
  body: 'review {{diff}}',
  updatedAt: null,
  palette: [{ name: 'diff', required: true, fenced: true, maxTokens: 24000, description: 'The diff.' }],
  lockedSuffixPreview: 'SECURITY: ... "findings"',
};
const reconcileView: api.PromptView = { ...reviewView, kind: 'reconcile', customized: true };
const followupView: api.PromptView = { ...reviewView, kind: 'followup' };

describe('PromptsSettings (list)', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([reviewView, reconcileView, followupView]);
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
    expect(api.fetchPrompt).toHaveBeenCalledWith('review');
  });
});
