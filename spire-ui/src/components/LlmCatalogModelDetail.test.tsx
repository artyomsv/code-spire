import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { catalogModel } from '../test/catalogFixtures';
import LlmCatalogModelDetail from './LlmCatalogModelDetail';

function row(label: string): HTMLElement {
  const term = screen.getByText(label, { selector: 'dt' });
  return term.nextElementSibling as HTMLElement;
}

describe('LlmCatalogModelDetail', () => {
  it('shows every price with its source link and observed date', () => {
    render(<LlmCatalogModelDetail model={catalogModel({ pricing: {
      input: 1, output: 2, cacheRead: 3, cacheWrite: 3, cacheWrite1h: 3, reasoning: 2, notBilled: [],
      sourceUrl: 'https://example.invalid/pricing', observedAt: '1970-01-01',
    } })} onClose={vi.fn()} />);

    expect(row('Input')).toHaveTextContent('$0.000/1M');
    expect(row('Output')).toHaveTextContent('/1M');
    expect(row('Cached input')).toHaveTextContent('/1M');
    expect(row('Cache write')).toHaveTextContent('/1M');
    expect(row('Reasoning')).toHaveTextContent('/1M');
    expect(row('Cache write (1 hour)')).toHaveTextContent('recorded, not charged');
    const source = within(row('Prices from')).getByRole('link');
    expect(source).toHaveAttribute('href', 'https://example.invalid/pricing');
    expect(row('Prices from')).toHaveTextContent('read 1970-01-01');
  });

  it('an unpriced type shows as not priced', () => {
    render(<LlmCatalogModelDetail model={catalogModel({ pricing: {
      input: 1, output: 2, cacheRead: null, cacheWrite: null, cacheWrite1h: null, reasoning: null,
      notBilled: ['CACHE_WRITE'], sourceUrl: 'https://example.invalid/pricing', observedAt: '1970-01-01',
    } })} onClose={vi.fn()} />);

    expect(row('Reasoning')).toHaveTextContent('not priced');
    expect(row('Cached input')).toHaveTextContent('not priced');
    expect(row('Cache write')).toHaveTextContent('not billed');
  });

  it('shows capabilities, limits and lifecycle', () => {
    render(<LlmCatalogModelDetail model={catalogModel({
      lifecycle: { status: 'DEPRECATED', deprecationDate: '1970-01-01', retirementDate: null, successor: 'TEST-cat-b' },
    })} onClose={vi.fn()} />);

    expect(row('Capabilities')).toHaveTextContent('Tools, Structured output, Prompt cache');
    expect(row('Context window')).toHaveTextContent('1');
    expect(row('Lifecycle')).toHaveTextContent('deprecated · deprecated 1970-01-01 · successor TEST-cat-b');
  });
});
