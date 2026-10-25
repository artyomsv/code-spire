import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import SpecificationView from './SpecificationView';
import type { WrittenArtifact } from './workDraftApi';

const DIGEST = 'a'.repeat(64);
const TICKET = 'https://tracker.example.test/TEST-owner/TEST-repo/issues/42';
const written = (comment: WrittenArtifact['comment']): WrittenArtifact => ({
  text: '## Outcome\nTEST-outcome <b>TEST-bold</b>\n\n## Acceptance criteria\n- TEST-criterion', sha256: DIGEST, comment,
});

afterEach(cleanup);

// A specification is model output from a ticket anyone could write: it is shown as text, never as markup.
it('renders the criteria as text', () => {
  const { container } = render(<SpecificationView specification={written(null)} ticketLink={TICKET} />);
  expect(screen.getByLabelText('Specification text')).toHaveTextContent('TEST-outcome <b>TEST-bold</b>');
  expect(screen.getByLabelText('Specification text')).toHaveTextContent('- TEST-criterion');
  expect(container.querySelector('b')).toBeNull();
});

it('shows the digest and the comment link, or the ticket link while the comment is pending', () => {
  const posted = 'https://tracker.example.test/TEST-owner/TEST-repo/issues/42#issuecomment-70001';
  render(<SpecificationView specification={written({ state: 'sent', ref: posted })} ticketLink={TICKET} />);
  expect(screen.getByText(DIGEST.slice(0, 12), { exact: false })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Posted to the ticket' })).toHaveAttribute('href', posted);
  cleanup();

  render(<SpecificationView specification={written({ state: 'pending', ref: null })} ticketLink={TICKET} />);
  expect(screen.getByRole('link', { name: 'Being posted to the ticket' })).toHaveAttribute('href', TICKET);
  cleanup();

  // A tracker that answers with a bare id has no address to open, so the ticket is linked instead.
  render(<SpecificationView specification={written({ state: 'sent', ref: '70001' })} ticketLink={TICKET} />);
  expect(screen.getByRole('link', { name: 'Posted to the ticket' })).toHaveAttribute('href', TICKET);
});

it('says plainly when the comment was not posted', () => {
  render(<SpecificationView specification={written({ state: 'refused', ref: null })} ticketLink={TICKET} />);
  expect(screen.getByText(/was not posted to the ticket/)).toBeInTheDocument();
});
