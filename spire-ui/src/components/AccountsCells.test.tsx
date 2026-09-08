import { describe, expect, it } from 'vitest';
import { shortConversation, storedState } from './AccountsCells';

/**
 * The table cell's short form of the conversation level. It used to be derived by comparing the
 * string the FORM renders, which failed in two silent ways: a reworded label widened the cell back,
 * and a level this build has never heard of read as "Inherit" — the reassuring answer rather than
 * the honest one, and the same shape as the `refused` status that once rendered as five green
 * segments.
 */
describe('shortConversation', () => {
  it('names each level the server knows', () => {
    expect(shortConversation('REPORT_ONLY')).toBe('Report-only');
    expect(shortConversation('EXPLAIN')).toBe('Explain');
    expect(shortConversation('INTERACTIVE')).toBe('Interactive');
  });

  it('says Inherit only when the account genuinely sets no level', () => {
    expect(shortConversation(null)).toBe('Inherit');
    expect(shortConversation(undefined)).toBe('Inherit');
    expect(shortConversation('')).toBe('Inherit');
  });

  /** The discriminating case: an unknown level must never be folded into a real one. */
  it('names an unknown level instead of calling it Inherit', () => {
    expect(shortConversation('WHISPER')).toBe('Unknown (WHISPER)');
  });
});

/**
 * What the registry stored, as a state. `idle` means nothing is stored — not "nothing was checked
 * this session", which is what a disabled account used to be drawn as while holding a rejection.
 */
describe('storedState', () => {
  it('reports a stored pass and a stored refusal', () => {
    expect(storedState({ lastCheckAt: '2026-07-27T10:00:00Z', lastCheckOk: true, lastCheckError: null })).toBe('ok');
    expect(
      storedState({ lastCheckAt: '2026-07-27T10:00:00Z', lastCheckOk: false, lastCheckError: 'Rejected' }),
    ).toBe('fail');
  });

  it('is idle only when a check is genuinely missing, either half of it', () => {
    expect(storedState({ lastCheckAt: null, lastCheckOk: null, lastCheckError: null })).toBe('idle');
    expect(storedState({ lastCheckAt: null, lastCheckOk: true, lastCheckError: null })).toBe('idle');
    expect(storedState({ lastCheckAt: '2026-07-27T10:00:00Z', lastCheckOk: null, lastCheckError: null })).toBe(
      'idle',
    );
  });
});
