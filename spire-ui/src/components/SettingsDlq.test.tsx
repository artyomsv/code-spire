import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import { queryWrapper } from '../test/queryWrapper';
import SettingsDlq from './SettingsDlq';

const entry = (over: Partial<api.DlqEntry> = {}): api.DlqEntry => ({
  id: 'TEST-dlq-1', kafkaKey: null, messageType: 'TEST-MESSAGE', originalTopic: 'TEST-topic', reason: null,
  payload: '{}', status: 'PENDING', createdAt: '1970-01-01T00:00:00Z', ...over,
});

describe('SettingsDlq', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'getDlqEntries').mockResolvedValue([entry()]);
    vi.spyOn(api, 'replayDlqEntry').mockResolvedValue(entry());
  });

  it('shows the entries at once when the page opens again', async () => {
    const wrapper = queryWrapper();
    const first = render(<SettingsDlq />, { wrapper });
    expect(await screen.findByText('TEST-MESSAGE')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.getDlqEntries).mockReturnValue(new Promise(() => {}));
    render(<SettingsDlq />, { wrapper });
    expect(screen.getByText('TEST-MESSAGE')).toBeInTheDocument();
  });

  // The server removed the replayed entry; the row goes at once, and a re-read must not bring it back.
  it('removes a replayed entry', async () => {
    render(<SettingsDlq />, { wrapper: queryWrapper() });
    fireEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    vi.mocked(api.getDlqEntries).mockResolvedValue([]);
    await waitFor(() => expect(screen.queryByText('TEST-MESSAGE')).toBeNull());
    expect(api.replayDlqEntry).toHaveBeenCalledWith('TEST-dlq-1');
  });
});
