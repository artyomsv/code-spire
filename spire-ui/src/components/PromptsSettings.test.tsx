import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import PromptsSettings from './PromptsSettings';
import * as api from '../api';

const view: api.PromptView = {
  kind: 'review',
  customized: false,
  system: 'persona',
  body: 'review {{diff}}',
  updatedAt: null,
  palette: [{ name: 'diff', required: true, fenced: true, maxTokens: 24000, description: 'The diff.' }],
  lockedSuffixPreview: 'SECURITY: ... "findings"',
};

describe('PromptsSettings', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrompts').mockResolvedValue([view]);
  });

  it('renders a kind with its variable palette', async () => {
    render(<PromptsSettings />);
    // Scoped to the heading/button roles rather than plain getByText: the fixture's body
    // text ("review {{diff}}") legitimately renders inside the body <textarea> too, so an
    // unscoped getByText(/review/i) or getByText(/\{\{diff\}\}/) would match that textarea
    // as well as the intended heading/chip and throw on multiple matches.
    await waitFor(() => expect(screen.getByRole('heading', { name: /review/i })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /\{\{diff\}\}/ })).toBeInTheDocument();
  });
});
