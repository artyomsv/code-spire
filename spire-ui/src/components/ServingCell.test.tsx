import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ServingCell from './ServingCell';
import { servingChip } from './servingAccounts';
import type { ServingAccount, ServingAccounts, ServingState } from '../api';

const account = (state: ServingState, name: string | null): ServingAccount => ({
  state,
  id: name === null ? null : `TEST-${name}`,
  name,
  botUsername: null,
  botAccountId: null,
});

const lookup = (reviewer: ServingAccount, factory: ServingAccount) => ({
  data: { type: 'github', workspace: 'TEST-acme', reviewer, factory } satisfies ServingAccounts,
});

describe('ServingCell', () => {
  /**
   * The cell is one chip and nothing else: the name of the account serving that role, in the tone
   * servingChip chose, with the state it means as the tooltip rather than as a second line.
   */
  it('names the account serving the role, in that state’s tone, with the state as its tooltip', () => {
    const reviewer = account('ok', 'reviewer-bot');
    render(<ServingCell role="reviewer" lookup={lookup(reviewer, account('missing', null))} />);

    const chip = screen.getByText('reviewer-bot').closest('.pill');
    expect(chip).toHaveClass('completed');
    expect(chip).toHaveAttribute('title', servingChip(reviewer, null).title);
  });

  /**
   * No button in the cell. The per-chip Verify said nothing about WHAT it verified, and beside the
   * factory chip a pass read as "can push" — a claim its read-only probe never made. Checking an
   * account lives on Accounts, and checking a repository lives in the webhook form.
   */
  it('says none for a role no account serves, and offers no button to press', () => {
    render(<ServingCell role="factory" lookup={lookup(account('ok', 'reviewer-bot'), account('missing', null))} />);

    expect(screen.getByText('none').closest('.pill')).toHaveClass('cancelled');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
