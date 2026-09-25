import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import HarnessSignInCode from './HarnessSignInCode';

// Placeholder link and code: this is about the buttons, not the vendor's real values.
const LINK = 'https://auth.example.test/device';
const CODE = 'TEST-12345';

let order: string[];

beforeEach(() => {
  order = [];
  Object.assign(navigator, { clipboard: { writeText: vi.fn(async (text: string) => { order.push(`copy:${text}`); }) } });
  vi.spyOn(window, 'open').mockImplementation((url?: string | URL) => { order.push(`open:${String(url)}`); return null; });
});
afterEach(cleanup);

it('copies the code from the button beside it', () => {
  render(<HarnessSignInCode verificationUri={LINK} userCode={CODE} remaining="13m 30s" />);

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code' }));

  expect(order).toEqual([`copy:${CODE}`]);
  expect(screen.getByRole('status')).toHaveTextContent('Code copied.');
});

// The page that opens asks for the code, so it is copied too — and FIRST: once the new tab has focus,
// a browser refuses a clipboard write.
it('copies the code and then opens the sign-in page in a new tab', () => {
  render(<HarnessSignInCode verificationUri={LINK} userCode={CODE} remaining="13m 30s" />);

  fireEvent.click(screen.getByRole('button', { name: 'Copy the code and open the sign-in page' }));

  expect(order).toEqual([`copy:${CODE}`, `open:${LINK}`]);
  expect(window.open).toHaveBeenCalledWith(LINK, '_blank', 'noopener,noreferrer');
  expect(screen.getByRole('status')).toHaveTextContent('Paste it on the page that just opened');
});

// The worker accepts only the vendor's own host; this is the second lock, where a click would follow it.
it('offers no link and no open button for an address that is not https', () => {
  render(<HarnessSignInCode verificationUri="http://auth.example.test/device" userCode={CODE} remaining="13m 30s" />);

  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Copy the code and open the sign-in page' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Copy the code' })).toBeInTheDocument();
});

it('says when the code has run out', () => {
  render(<HarnessSignInCode verificationUri={LINK} userCode={CODE} remaining={null} />);

  expect(screen.getByRole('status')).toHaveTextContent('The code has expired.');
});
