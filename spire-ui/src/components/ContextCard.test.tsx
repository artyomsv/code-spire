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

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

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

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

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

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(screen.queryByRole('button', { name: /comment/i })).not.toBeInTheDocument();
  });

  /**
   * A comment can contain its own markdown list ("see below:" followed by bullet points). Only
   * lines shaped like a comment header ("- author: text") may count as a comment — a bare bullet
   * inside a comment's body must not inflate the count.
   */
  it('counts only comment headers, not markdown bullets inside a comment', async () => {
    const withNestedList = {
      ...item,
      body:
        'State: open\n\nRecent comments:\n' +
        '- alice: see below:\n- first point\n- second point\n- bob: thanks',
    };
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [withNestedList],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    fireEvent.click(await screen.findByRole('button', { name: /Cap discounts at 50%/ }));
    expect(await screen.findByRole('button', { name: /2 comments/i })).toBeInTheDocument();
  });

  /** No context is the normal path with no provider configured — not a failure. */
  it('explains an empty context instead of showing an error', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    expect(await screen.findByText(/No context was resolved/i)).toBeInTheDocument();
  });

  /** The description costs an SCM call, so it must not be paid for on every page load. */
  /** The description is shown outright, not behind a toggle, so it is fetched with the card. */
  it('fetches and shows the description without any interaction', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    expect(await screen.findByText('Implements #7')).toBeInTheDocument();
    expect(api.fetchPrDescription).toHaveBeenCalledWith('acme', 'widgets', 7);
    expect(screen.queryByRole('button', { name: /pull request description/i })).not.toBeInTheDocument();
  });

  /** The section carries its own label, so the description is identifiable without a caption. */
  it('labels the description section', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    expect(await screen.findByText(/pull request description/i)).toBeInTheDocument();
  });

  /**
   * A fetch failure (network, 5xx, rejected credential) must read differently from a review that
   * genuinely resolved no context — otherwise an operator can't tell "nothing was configured" from
   * "we couldn't even ask", the same silent-failure shape that bit the turn cap before.
   */
  it('shows a failure message instead of the empty-context message when the fetch fails', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(api, 'fetchReviewContext').mockRejectedValue(new Error('network error'));

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    expect(await screen.findByText(/Could not load the context/i)).toBeInTheDocument();
    expect(screen.queryByText(/No context was resolved/i)).not.toBeInTheDocument();
  });

  /**
   * A revoked bot token or a network error must not be indistinguishable from a pull request that
   * genuinely has no description — the same "—" placeholder in both cases hid a credential problem
   * from the operator.
   */
  it('shows a failure message instead of the empty placeholder when the description fetch fails', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });
    vi.spyOn(api, 'fetchPrDescription').mockRejectedValue(new Error('The stored credential was rejected.'));

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    expect(await screen.findByText(/description could not be loaded/i)).toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
  });

  it('renders no link for an item with an unsafe uri', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [{ ...item, uri: 'javascript:alert(1)' }],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    await screen.findByText(/Cap discounts at 50%/);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('renders a link for an item with an https uri', async () => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });

    render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc123" contextReady />);

    const link = await screen.findByRole('link');
    expect(link).toHaveAttribute('href', item.uri);
    // The shared icon-button pattern, same as the header's open-in-provider control — the label
    // is what makes an icon-only control usable, so it is pinned here rather than left to CSS.
    expect(link).toHaveClass('icon-btn');
    expect(link).toHaveAccessibleName(/open in the issue tracker/i);
  });

  /** Every sibling card live-updates on a re-run; this one must too, or it keeps showing the
   *  previous run's items after the pipeline has moved on to a new commit. */
  it('refetches when the review advances to a new commit', async () => {
    const fetchSpy = vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [item],
      contributingSources: [],
      missingSources: [],
    });
    // The spy is shared across this file's tests (vi.spyOn re-wraps the same mock), so its call
    // count going in reflects earlier tests, not this one — start from a clean count.
    fetchSpy.mockClear();

    const { rerender } = render(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc111" contextReady />);
    await screen.findByText(/Cap discounts at 50%/);
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    rerender(<ContextCard workspace="acme" slug="widgets" pr={7} sha="def222" contextReady />);
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2));
  });

  /**
   * The re-run case above keys on the commit, which is what a NEW run changes. Within one run
   * nothing it watched moved: context is assembled at the same commit the card already mounted on,
   * so a page opened while the review was still running fetched once, found nothing, and never
   * asked again. The card sat empty until a manual refresh while every sibling card updated live.
   */
  it('refetches when context is assembled during the run it is already watching', async () => {
    const fetchSpy = vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });
    fetchSpy.mockClear();

    // Mounted mid-review: the diff is fetched, context has not been assembled yet.
    const { rerender } = render(
      <ContextCard workspace="acme" slug="widgets" pr={7} sha="abc111" contextReady={false} />,
    );
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));

    fetchSpy.mockResolvedValue({ items: [item], contributingSources: [], missingSources: [] });
    // Same commit — only the pipeline stage moved, which is all the socket tells us.
    rerender(<ContextCard workspace="acme" slug="widgets" pr={7} sha="abc111" contextReady />);

    expect(await screen.findByText(/Cap discounts at 50%/)).toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });
});
