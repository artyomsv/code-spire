import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import PromptScopePicker from './PromptScopePicker';
import PromptDetail from './PromptDetail';
import PromptsSettings from './PromptsSettings';
import * as api from '../api';

/**
 * Task 23's scope selector. The provenance tests (3 and 4) exercise `PromptDetail`, not the
 * picker itself -- they're here because they're the point of the picker: a reader who cannot
 * tell at a glance which text a review will use has a worse tool than the global-only editor
 * this replaces. Both fixtures agree on `customized` with `inheritedFrom`, which is exactly the
 * wrong-but-plausible wiring to miss -- a UI that showed the REQUESTED scope instead of the
 * SUPPLYING one would pass every test whose fixture happens to have the two agree, and neither
 * of these does.
 */

function baseView(): api.PromptView {
  return {
    kind: 'review',
    scope: '*',
    inheritedFrom: 'default',
    customized: false,
    system: 'persona',
    body: 'review {{diff}}',
    updatedAt: null,
    palette: [],
    lockedSuffixPreview: 'SECURITY: ... "findings"',
    baseKnown: true,
    defaultDrifted: false,
    currentDefaultSystem: 'persona',
    currentDefaultBody: 'review {{diff}}',
    baseSystem: null,
    baseBody: null,
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

describe('PromptScopePicker', () => {
  it('defaults to global', async () => {
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue(['acme/widgets']);
    const onChange = vi.fn();
    render(<PromptScopePicker value="*" onChange={onChange} />);

    expect(await screen.findByDisplayValue(/global/i)).toBeInTheDocument();
  });

  it('lists the repositories the deployment has seen', async () => {
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue(['acme/widgets', 'acme/tools']);
    render(<PromptScopePicker value="*" onChange={vi.fn()} />);

    expect(await screen.findByRole('option', { name: 'acme/widgets' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'acme/tools' })).toBeInTheDocument();
  });

  it('says which level the shown text actually came from', async () => {
    // A reader who cannot tell at a glance which text a review will use has a worse tool than the
    // global-only one this replaces.
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: 'acme/widgets', inheritedFrom: 'global', customized: false,
    });
    renderWithRouter('/settings/prompts/review?scope=acme/widgets');

    expect(await screen.findByText(/inherited from global/i)).toBeInTheDocument();
  });

  it('marks a template overridden at this repo', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: 'acme/widgets', inheritedFrom: 'repo', customized: true,
    });
    renderWithRouter('/settings/prompts/review?scope=acme/widgets');

    expect(await screen.findByText(/overridden for this repository/i)).toBeInTheDocument();
  });

  it('does not call the global default text "inherited from global"', async () => {
    // The one combination that must NOT read "inherited" -- scope IS global and global itself
    // supplies the text, so there is nothing above it to inherit from.
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: '*', inheritedFrom: 'global', customized: true,
    });
    renderWithRouter('/settings/prompts/review');

    await screen.findByText(/applies to every repository/i);
    expect(screen.queryByText(/inherited from global/i)).not.toBeInTheDocument();
  });

  it('keeps the scope when navigating back to the prompt list', async () => {
    vi.spyOn(api, 'fetchPrompt').mockResolvedValue({
      ...baseView(), scope: 'acme/widgets', inheritedFrom: 'repo', customized: true,
    });
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([]);
    vi.spyOn(api, 'fetchPromptScopes').mockResolvedValue([]);
    render(
      <MemoryRouter initialEntries={['/settings/prompts/review?scope=acme/widgets']}>
        <Routes>
          <Route path="/settings/prompts" element={<PromptsSettings />} />
          <Route path="/settings/prompts/:kind" element={<PromptDetail />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /all prompts/i }));

    await waitFor(() => expect(api.fetchPrompts).toHaveBeenCalledWith('acme/widgets'));
  });
});
