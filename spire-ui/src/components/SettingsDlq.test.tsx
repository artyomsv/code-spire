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

  // Review of PR 360, M-2: a failed re-read is shown beside the last list, and an old empty list claims nothing.
  it('shows a failed re-read beside the last entries', async () => {
    const wrapper = queryWrapper();
    const first = render(<SettingsDlq />, { wrapper });
    expect(await screen.findByText('TEST-MESSAGE')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.getDlqEntries).mockRejectedValue(new Error('TEST-BOOM'));
    render(<SettingsDlq />, { wrapper });
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-BOOM');
    expect(screen.getByText('TEST-MESSAGE')).toBeInTheDocument();
  });

  it('does not call an old empty list healthy when the re-read failed', async () => {
    vi.mocked(api.getDlqEntries).mockResolvedValue([]);
    const wrapper = queryWrapper();
    const first = render(<SettingsDlq />, { wrapper });
    expect(await screen.findByText('No dead-lettered messages')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.getDlqEntries).mockRejectedValue(new Error('TEST-BOOM'));
    render(<SettingsDlq />, { wrapper });
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-BOOM');
    expect(screen.queryByText('No dead-lettered messages')).toBeNull();
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
