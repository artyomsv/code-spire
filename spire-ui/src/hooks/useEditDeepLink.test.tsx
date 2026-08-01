import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { useEditDeepLink } from './useEditDeepLink';

interface Record {
  id: string;
  name: string;
}

/**
 * A harness standing in for a settings page: it holds a list that may arrive late, reports which
 * record the hook asked it to open, and renders the current URL so the param-stripping is
 * observable.
 */
function Harness({ records, open }: { records: Record[]; open: (r: Record) => void }) {
  useEditDeepLink(records, open);
  const location = useLocation();
  return <span data-testid="url">{location.pathname + location.search}</span>;
}

const renderAt = (url: string, records: Record[], open: (r: Record) => void) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Harness records={records} open={open} />
    </MemoryRouter>,
  );

describe('useEditDeepLink', () => {
  it('opens the record the URL names', async () => {
    const open = vi.fn();
    const records = [
      { id: 'TEST-id-1', name: 'first' },
      { id: 'TEST-id-2', name: 'second' },
    ];
    renderAt('/settings/webhooks?edit=TEST-id-2', records, open);
    await waitFor(() => expect(open).toHaveBeenCalledWith(records[1]));
  });

  /**
   * Leaving the param would reopen the dialog every time the operator closed it and the component
   * re-rendered, and a refresh or bookmark would spring it open long after the cause was fixed.
   */
  it('strips the param once consumed', async () => {
    renderAt('/settings/webhooks?edit=TEST-id-1', [{ id: 'TEST-id-1', name: 'first' }], vi.fn());
    await waitFor(() =>
      expect(screen.getByTestId('url')).toHaveTextContent('/settings/webhooks'),
    );
    expect(screen.getByTestId('url').textContent).not.toContain('edit=');
  });

  it('opens nothing when the URL names no record', async () => {
    const open = vi.fn();
    renderAt('/settings/webhooks', [{ id: 'TEST-id-1', name: 'first' }], open);
    await waitFor(() => expect(screen.getByTestId('url')).toBeInTheDocument());
    expect(open).not.toHaveBeenCalled();
  });

  /**
   * The list is normally still loading on first render, so an unmatched id must not be treated as
   * an error — and the param has to survive for the load that can actually consume it.
   */
  it('waits for a list that arrives late, keeping the param meanwhile', async () => {
    const open = vi.fn();
    const { rerender } = render(
      <MemoryRouter initialEntries={['/settings/webhooks?edit=TEST-id-1']}>
        <Harness records={[]} open={open} />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent('edit=TEST-id-1'));
    expect(open).not.toHaveBeenCalled();

    const arrived = [{ id: 'TEST-id-1', name: 'first' }];
    rerender(
      <MemoryRouter initialEntries={['/settings/webhooks?edit=TEST-id-1']}>
        <Harness records={arrived} open={open} />
      </MemoryRouter>,
    );
    await waitFor(() => expect(open).toHaveBeenCalledWith(arrived[0]));
  });

  /** A record deleted since the row was rendered must not throw. */
  it('opens nothing when the named record is absent', async () => {
    const open = vi.fn();
    renderAt('/settings/webhooks?edit=TEST-gone', [{ id: 'TEST-id-1', name: 'first' }], open);
    await waitFor(() => expect(screen.getByTestId('url')).toBeInTheDocument());
    expect(open).not.toHaveBeenCalled();
  });
});
