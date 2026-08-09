import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import ReviewCostCard from './ReviewCostCard';
import type { ChargeLineView } from '../api';

const line = (overrides: Partial<ChargeLineView>): ChargeLineView => ({
  callRef: 'CANARY-CALL-1',
  kind: 'REVIEW',
  model: 'TEST-MODEL',
  tokenType: 'INPUT',
  tokens: 1000,
  costMillicents: 250,
  pricingMode: 'METERED',
  pricedAt: '2026-08-06T00:00:00Z',
  ...overrides,
});

/**
 * The card's job is to make a partial total legible. A total that silently omits what could not be
 * priced reads as complete, which is the same defect as the zero it replaced — one layer up.
 */
describe('ReviewCostCard', () => {
  it('groups lines by call and shows each token type its own cost', () => {
    render(
      <ReviewCostCard
        lines={[
          line({ tokenType: 'INPUT', tokens: 1000, costMillicents: 250 }),
          line({ tokenType: 'CACHED_INPUT', tokens: 4000, costMillicents: 100 }),
        ]}
        unpricedCalls={0}
      />,
    );

    expect(screen.getByText(/cached input/i)).toBeInTheDocument();
    // The token count IS asserted, with the group separator left open: `toLocaleString()` follows the
    // machine's locale, so the digits are pinned and only the separator tolerated. Pinning a locale in
    // the test would assert something production does not do; leaving the count unasserted would let
    // the wrong number through. Neither is safe.
    //
    // `[^0-9]?` rather than an enumerated class of separators: this machine resolves to `en-CH`, which
    // groups with U+2019 (a right single quotation mark, "4’000") — NOT the ASCII apostrophe an
    // enumerated `[,.']` would have covered. Every locale's group separator is a single non-digit
    // character, so matching "one optional non-digit" is tolerant without needing that list kept current.
    expect(screen.getByText(/4[^0-9]?000 tokens/)).toBeInTheDocument();
    // Each line's cost is asserted directly, not just for presence. No rate appears: the server does
    // not send one (a rate is admin-only configuration and this page is viewer-visible), so asserting
    // its absence here is what would catch it being reintroduced into the payload and rendered.
    expect(screen.getByText(/1[^0-9]?000 tokens · \$0\.003/)).toBeInTheDocument();
    expect(screen.getByText(/4[^0-9]?000 tokens · \$0\.001/)).toBeInTheDocument();
    expect(screen.queryByText(/\/1M tokens/)).not.toBeInTheDocument();
    expect(screen.queryByText(/could not be priced/i)).not.toBeInTheDocument();
  });

  it('says the total is partial when a call could not be priced', () => {
    render(
      <ReviewCostCard
        lines={[line({ costMillicents: null, pricingMode: 'UNKNOWN' })]}
        unpricedCalls={1}
      />,
    );

    // Scoped to the total's own note rather than matched anywhere on the card: the phrase also
    // appears on the line row, so an unscoped match passed even with the qualifier deleted — the
    // total, not the breakdown, is the figure a spend decision reads.
    const partial = document.querySelector('.usage-partial') as HTMLElement | null;
    expect(partial).not.toBeNull();
    expect(partial).toHaveTextContent(/1 call could not be priced — this total is partial/i);
  });

  /**
   * The all-unpriced case, which is where a missing qualifier is worst: every line contributes
   * nothing, so the total is $0.000 — a figure identical to an unmetered model's asserted zero. The
   * qualifier is the only thing distinguishing them, so it must be present, not merely possible.
   */
  it('qualifies a zero total when every call was unpriceable', () => {
    render(
      <ReviewCostCard
        lines={[
          line({ callRef: 'CANARY-CALL-3', tokenType: 'INPUT', costMillicents: null, pricingMode: 'UNKNOWN' }),
          line({ callRef: 'CANARY-CALL-4', tokenType: 'OUTPUT', costMillicents: null, pricingMode: 'UNKNOWN' }),
        ]}
        unpricedCalls={2}
      />,
    );

    const total = document.querySelector('.usage-total') as HTMLElement;
    expect(total).toHaveTextContent('$0.000');
    expect(document.querySelector('.usage-partial')).toHaveTextContent(
      /2 calls could not be priced — this total is partial/i,
    );
  });

  it('labels an unmetered call as self-hosted rather than as free', () => {
    render(
      <ReviewCostCard
        lines={[line({ costMillicents: 0, pricingMode: 'UNMETERED' })]}
        unpricedCalls={0}
      />,
    );

    const callBlock = document.querySelector('.usage-call') as HTMLElement;
    expect(within(callBlock).getAllByText(/self-hosted/i).length).toBeGreaterThan(0);
    // The call's own cost line must say "self-hosted", not a dollar figure — the total below it
    // legitimately shows $0.000 (it really is free), so that check has to stay scoped to the call.
    expect(within(callBlock).queryByText(/\$0\.000/)).not.toBeInTheDocument();
  });

  it('renders an empty state rather than an empty card when no calls have happened yet', () => {
    render(<ReviewCostCard lines={[]} unpricedCalls={0} />);

    expect(screen.getByText(/no model calls recorded yet/i)).toBeInTheDocument();
  });

  /** A call's total sums non-null lines only — a null contributes nothing, but it must not turn the
   *  whole call's total into NaN or a silent zero either. */
  it('sums only the priced lines of a call that is partly unpriced', () => {
    render(
      <ReviewCostCard
        lines={[
          line({ tokenType: 'INPUT', costMillicents: 250, pricingMode: 'METERED' }),
          line({ tokenType: 'OUTPUT', costMillicents: null, pricingMode: 'UNKNOWN' }),
        ]}
        unpricedCalls={1}
      />,
    );

    expect(screen.getByText('$0.003 (partial)')).toBeInTheDocument();
  });

  /**
   * The regression this guards: grouping on `kind`+`pricedAt` (as the card originally did) splits
   * one call into one block per line whenever its lines don't share a timestamp — which they never
   * reliably do, since each is written in its own transaction server-side. Two lines of one call
   * with DIFFERENT `pricedAt` values must still render as exactly one block.
   */
  it('groups lines sharing one callRef into a single call even with different pricedAt values', () => {
    render(
      <ReviewCostCard
        lines={[
          line({ callRef: 'CANARY-CALL-2', tokenType: 'INPUT', pricedAt: '2026-08-06T00:00:00.100Z' }),
          line({ callRef: 'CANARY-CALL-2', tokenType: 'OUTPUT', pricedAt: '2026-08-06T00:00:00.900Z' }),
        ]}
        unpricedCalls={0}
      />,
    );

    expect(document.querySelectorAll('.usage-call')).toHaveLength(1);
    expect(screen.getByText(/1 request/)).toBeInTheDocument();
  });
});
