import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import PromptDetail from './PromptDetail';
import * as api from '../api';
import { renderWithQuery } from '../test/queryWrapper';

/**
 * Reset to default deletes the custom text saved for the scope, and nothing keeps a copy, so the
 * button only asks; the prompt is reset when the dialog's Reset is pressed.
 */

function customizedView(): api.PromptView {
  return {
    kind: 'review',
    scope: '*',
    inheritedFrom: 'global',
    customized: true,
    system: 'TEST persona',
    body: 'Diff:\n{{diff}}',
    updatedAt: '2026-08-20T10:00:00Z',
    palette: [],
    lockedSuffixPreview: 'TEST suffix',
    baseKnown: true,
    defaultDrifted: false,
    currentDefaultSystem: 'TEST default persona',
    currentDefaultBody: 'Diff:\n{{diff}}',
    baseSystem: 'TEST default persona',
    baseBody: 'Diff:\n{{diff}}',
  };
}

function renderPrompt() {
  vi.spyOn(api, 'fetchPrompt').mockResolvedValue(customizedView());
  renderWithQuery(
    <MemoryRouter initialEntries={['/settings/prompts/review']}>
      <Routes>
        <Route path="/settings/prompts/:kind" element={<PromptDetail />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('reset to default', () => {
  it('asks first, naming the prompt and what is lost', async () => {
    const reset = vi.spyOn(api, 'resetPrompt').mockResolvedValue();
    renderPrompt();

    fireEvent.click(await screen.findByRole('button', { name: /reset to default/i }));

    expect(screen.getByRole('dialog')).toHaveTextContent(
      'Reset Review to its default? The custom text saved for this scope is deleted and cannot be restored.',
    );
    expect(reset).not.toHaveBeenCalled();
  });

  it('resets once the dialog is confirmed', async () => {
    const reset = vi.spyOn(api, 'resetPrompt').mockResolvedValue();
    renderPrompt();

    fireEvent.click(await screen.findByRole('button', { name: /reset to default/i }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(reset).toHaveBeenCalledWith('review', '*'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('keeps the custom text when the dialog is cancelled', async () => {
    const reset = vi.spyOn(api, 'resetPrompt').mockResolvedValue();
    renderPrompt();

    fireEvent.click(await screen.findByRole('button', { name: /reset to default/i }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(reset).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('TEST persona')).toBeInTheDocument();
  });
});
