import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import { queryWrapper, renderWithQuery } from '../test/queryWrapper';
import ReviewModeToggle from './ReviewModeToggle';

describe('ReviewModeToggle', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'getReviewMode').mockResolvedValue({ mode: 'active' });
  });

  it('shows the mode at once when it is drawn again', async () => {
    const wrapper = queryWrapper();
    const first = renderWithQuery(<ReviewModeToggle />, { wrapper });
    expect(await screen.findByText('Active')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.getReviewMode).mockReturnValue(new Promise(() => {}));
    renderWithQuery(<ReviewModeToggle />, { wrapper });
    expect(screen.getByRole('switch', { name: 'Active reviewing' })).toBeChecked();
  });

  it('restores the previous mode when the save fails', async () => {
    vi.spyOn(api, 'setReviewMode').mockRejectedValue(new Error('TEST-BOOM'));
    renderWithQuery(<ReviewModeToggle />);
    expect(await screen.findByText('Active')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: 'Active reviewing' }));
    expect(await screen.findByText('TEST-BOOM')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Active reviewing' })).toBeChecked();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });
});
