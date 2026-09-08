import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import AccountsTabs from './AccountsTabs';

/**
 * Plain anchors on purpose: `SettingsOperators` is rendered without a router in its own tests and
 * in the People tab under one, and the strip must work in both. The hash hrefs are what the rail
 * uses, so the two navigate the same way.
 */
describe('AccountsTabs', () => {
  it('offers both tabs and marks the active one as the current page', () => {
    render(<AccountsTabs active="people" />);
    const machine = screen.getByRole('link', { name: 'Machine accounts' });
    const people = screen.getByRole('link', { name: 'People' });
    expect(machine).toHaveAttribute('href', '#/settings/accounts');
    expect(people).toHaveAttribute('href', '#/settings/accounts/people');
    expect(people).toHaveAttribute('aria-current', 'page');
    expect(machine).not.toHaveAttribute('aria-current');
  });

  it('marks Machine accounts when that tab is active', () => {
    render(<AccountsTabs active="machine" />);
    expect(screen.getByRole('link', { name: 'Machine accounts' })).toHaveAttribute('aria-current', 'page');
  });
});
