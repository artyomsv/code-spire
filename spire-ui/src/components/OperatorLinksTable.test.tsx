import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { OperatorLinksTable } from './OperatorLinksTable';
import * as api from '../api';

/**
 * Unlinking stops an operator seeing their own numbers for that account, so the trash icon only
 * asks; nothing is unlinked until the dialog's Unlink is pressed.
 */

const LINKS: api.OperatorIdentityLink[] = [
  { oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: '3218389', authorHandle: 'TEST-handle' },
];
const OPERATORS: api.SeenOperator[] = [
  { subject: 'TEST-SUBJECT-1', username: 'test-user-one', displayName: 'TEST Operator One' },
];

function renderTable(onUnlinked = vi.fn()) {
  render(<OperatorLinksTable links={LINKS} operators={OPERATORS} candidates={[]} onUnlinked={onUnlinked} />);
  fireEvent.click(screen.getByRole('button', { name: 'Unlink TEST-SUBJECT-1 on github' }));
  return screen.getByRole('dialog');
}

describe('OperatorLinksTable unlinking', () => {
  it('asks first, naming the operator and the account', () => {
    const unlink = vi.spyOn(api, 'unlinkOperatorIdentity').mockResolvedValue(undefined);

    expect(renderTable()).toHaveTextContent('Unlink TEST Operator One · test-user-one from github · TEST-handle?');
    expect(unlink).not.toHaveBeenCalled();
  });

  it('unlinks once the dialog is confirmed', async () => {
    const unlink = vi.spyOn(api, 'unlinkOperatorIdentity').mockResolvedValue(undefined);
    const onUnlinked = vi.fn();

    fireEvent.click(within(renderTable(onUnlinked)).getByRole('button', { name: 'Unlink' }));

    await waitFor(() => expect(unlink).toHaveBeenCalledWith('TEST-SUBJECT-1', 'github'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(onUnlinked).toHaveBeenCalled();
  });

  it('keeps the link when the dialog is cancelled', () => {
    const unlink = vi.spyOn(api, 'unlinkOperatorIdentity').mockResolvedValue(undefined);

    fireEvent.click(within(renderTable()).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(unlink).not.toHaveBeenCalled();
  });
});
