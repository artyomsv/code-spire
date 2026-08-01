import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ContextCard, { splitComments } from './ContextCard';
import * as api from '../api';

const item = {
  kind: 'ISSUE',
  title: 'acme/widgets#7 Cap discounts at 50%',
  body: 'State: open\n\nMust reject above 50.\n\nRecent comments:\n- alice: agreed',
  uri: 'https://example.invalid/issues/7',
};

describe('splitComments', () => {
  it('separates the comment block from the detail', () => {
    expect(splitComments(item.body)).toEqual({
      detail: 'State: open\n\nMust reject above 50.',
      comments: '- alice: agreed',
    });
  });

  it('returns no comments when the marker is absent', () => {
    expect(splitComments('State: open\n\nJust a body.')).toEqual({
      detail: 'State: open\n\nJust a body.',
      comments: null,
    });
  });

  /**
   * One provider strips the assembled body, so a ticket with no description at all begins directly
   * with the marker and never carries the leading blank line.
   */
  it('splits when the body begins with the marker', () => {
    expect(splitComments('Recent comments:\n- alice: agreed')).toEqual({
      detail: '',
      comments: '- alice: agreed',
    });
  });

  /** "Recent comments:" inside prose is not a section header and must not split the body. */
  it('ignores the phrase when it is not on its own line', () => {
    expect(splitComments('See the Recent comments: section below for detail.')).toEqual({
      detail: 'See the Recent comments: section below for detail.',
      comments: null,
    });
  });
});

describe('ContextCard', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchPrDescription').mockResolvedValue('Implements #7');
  });

  it('shows each item with its body and comments collapsed', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: ['github-issues'],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    expect(await screen.findByText(/Cap discounts at 50%/)).toBeInTheDocument();
    expect(screen.queryByText(/Must reject above 50/)).not.toBeInTheDocument();
    expect(screen.queryByText(/alice: agreed/)).not.toBeInTheDocument();
  });

  it('reveals the comments only when their own toggle is used', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(await screen.findByText(/Must reject above 50/)).toBeInTheDocument();
    expect(screen.queryByText(/alice: agreed/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /1 comment/i }));
    expect(await screen.findByText(/alice: agreed/)).toBeInTheDocument();
  });

  it('renders no comments toggle when the item has none', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [{ ...item, body: 'State: open\n\nNo discussion.' }],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(screen.queryByRole('button', { name: /comment/i })).not.toBeInTheDocument();
  });

  /** No context is the normal path with no provider configured — not a failure. */
  it('explains an empty context instead of showing an error', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    expect(await screen.findByText(/No context was resolved/i)).toBeInTheDocument();
  });

  /** The description costs an SCM call, so it must not be paid for on every page load. */
  it('does not fetch the description until it is expanded', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);
    await screen.findByText(/Cap discounts at 50%/);
    expect(api.fetchPrDescription).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /description/i }));
    await waitFor(() => expect(api.fetchPrDescription).toHaveBeenCalledWith('acme', 'widgets', 7));
  });

  /**
   * A fetch failure (network, 5xx, rejected credential) must read differently from a review that
   * genuinely resolved no context — otherwise an operator can't tell "nothing was configured" from
   * "we couldn't even ask", the same silent-failure shape that bit the turn cap before.
   */
  it('shows a failure message instead of the empty-context message when the fetch fails', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(api, 'fetchReviewContext').mockRejectedValue(new Error('network error'));

    render(<ContextCard workspace="acme" slug="widgets" pr={7} />);

    expect(await screen.findByText(/Could not load the context/i)).toBeInTheDocument();
    expect(screen.queryByText(/No context was resolved/i)).not.toBeInTheDocument();
  });
});
