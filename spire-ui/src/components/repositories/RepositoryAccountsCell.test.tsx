import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import RepositoryAccountsCell from './RepositoryAccountsCell';
import type { Repository } from './repositoriesApi';

function show(state: string | null) {
  const reviewer = state === null ? null : { id: 'TEST-account', name: 'TEST-reviewer', role: 'REVIEWER', handle: 'TEST-bot', state };
  render(<RepositoryAccountsCell repository={{ reviewer, factory: null } as Repository} />);
  return screen.getByText(/^Reviewer:/);
}
it('shows a configured account as usable', () => { expect(show('configured')).toHaveClass('completed'); });
it('shows a disabled account as disabled', () => { expect(show('disabled')).toHaveClass('cancelled'); });
it('shows an absent account as missing', () => {
  const cell = show(null); expect(cell).toHaveClass('cancelled'); expect(cell).toHaveTextContent('No account selected');
});
it('never shows an unknown serving state as usable', () => { expect(show('TEST-unknown')).toHaveClass('refused'); });
