import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import PromptDetail from './PromptDetail';
import * as api from '../api';

/**
 * Task 18's drift fields (`baseKnown`/`defaultDrifted`/`baseSystem`/`baseBody`/
 * `currentDefaultSystem`/`currentDefaultBody`) only matter if the UI does something with them.
 * These cover the banner text, the two distinct actions it wires to, and the baseKnown=false
 * branch that must not read as "up to date" — see `PromptDriftBanner.tsx`.
 */

function driftedView(): api.PromptView {
  return {
    kind: 'review',
    scope: '*',
    inheritedFrom: 'global',
    customized: true,
    system: 'My persona',
    body: 'Diff:\n{{diff}}',
    updatedAt: '2026-08-20T10:00:00Z',
    palette: [],
    lockedSuffixPreview: 'SECURITY: ... "findings"',
    baseKnown: true,
    defaultDrifted: true,
    currentDefaultSystem: 'A newer shipped persona',
    currentDefaultBody: 'Diff:\n{{diff}}',
    baseSystem: 'AN OLDER SHIPPED PERSONA',
    baseBody: 'Diff:\n{{diff}}',
  };
}

function renderWithRouter(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/settings/prompts/:kind" element={<PromptDetail />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('prompt default drift', () => {
  it('shows what changed in the shipped prompt', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(driftedView());
    renderWithRouter('/settings/prompts/review');

    expect(await screen.findByText(/the built-in prompt has changed/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /take the new default/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /keep mine/i })).toBeInTheDocument();
  });

  it('keep mine re-stamps the ancestor without touching the text', async () => {
    const accept = vi.spyOn(api, 'acceptPromptDefault').mockResolvedValue();
    const reset = vi.spyOn(api, 'resetPrompt').mockResolvedValue();
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue(driftedView());
    renderWithRouter('/settings/prompts/review');

    fireEvent.click(await screen.findByRole('button', { name: /keep mine/i }));

    expect(accept).toHaveBeenCalledWith('review', '*');
    expect(reset).not.toHaveBeenCalled();
  });

  it('says the ancestor is unknown rather than claiming up to date', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({ ...driftedView(), baseKnown: false, defaultDrifted: false });
    renderWithRouter('/settings/prompts/review');

    expect(await screen.findByText(/customized before default tracking began/i)).toBeInTheDocument();
  });

  it('offers no default diff when there is nothing to diff against', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({ ...driftedView(), baseKnown: false, defaultDrifted: false });
    renderWithRouter('/settings/prompts/review');

    await screen.findByText(/customized before default tracking began/i);
    expect(screen.queryByText(/the built-in prompt has changed/i)).not.toBeInTheDocument();
  });

  /**
   * A repo scope with no override of its own, inheriting a drifted GLOBAL row: `effective` resolves
   * the global row and reports ITS drift, but `drift`/`acceptCurrentDefault`/`reset` are scope-exact.
   * Showing the banner here would offer two actions that match zero rows at this scope — neither
   * dismissible. The "Custom" badge has the same bug: `customized` is `row.isPresent()` on the
   * RESOLVED row, so it would sit right above "Inherited from global".
   */
  it('shows no drift banner and no Custom badge for a repo scope inheriting a drifted global row', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...driftedView(),
      scope: 'acme/widgets',
      inheritedFrom: 'global',
    });
    renderWithRouter('/settings/prompts/review?scope=acme%2Fwidgets');

    await screen.findByText(/inherited from global/i);
    expect(screen.queryByText(/the built-in prompt has changed/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Custom')).not.toBeInTheDocument();
  });
});
