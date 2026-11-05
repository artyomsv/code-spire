import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AUTH_LOG_KEY,
  AUTH_LOG_MAX,
  LEGACY_AUTH_LOG_KEY,
  readAuthLog,
  recordAuthEvent,
  recordPageLoad,
} from './authLog';

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

const silenceConsole = () => vi.spyOn(console, 'info').mockImplementation(() => undefined);

describe('the sign-in diagnostic log', () => {
  it('keeps each event with its time, across reads', () => {
    silenceConsole();
    recordAuthEvent('login', 'TEST-reason');

    const [entry] = readAuthLog();
    expect(entry.event).toBe('login');
    expect(entry.detail).toBe('TEST-reason');
    expect(Number.isNaN(Date.parse(entry.at))).toBe(false);
  });

  it('prints each event to the console as it happens', () => {
    const info = silenceConsole();
    recordAuthEvent('login', 'TEST-reason');

    expect(info).toHaveBeenCalledWith('[spire auth]', expect.any(String), 'login', 'TEST-reason');
  });

  it('keeps only the newest entries', () => {
    silenceConsole();
    for (let i = 0; i < AUTH_LOG_MAX + 5; i++) recordAuthEvent('login', `TEST-${i}`);

    const log = readAuthLog();
    expect(log).toHaveLength(AUTH_LOG_MAX);
    expect(log[0].detail).toBe('TEST-5');
    expect(log[AUTH_LOG_MAX - 1].detail).toBe(`TEST-${AUTH_LOG_MAX + 4}`);
  });

  it('starts again from a stored value it cannot read', () => {
    silenceConsole();
    sessionStorage.setItem(AUTH_LOG_KEY, 'TEST-not-json');
    recordAuthEvent('login', 'TEST-reason');

    expect(readAuthLog().map((e) => e.detail)).toEqual(['TEST-reason']);
  });

  it('never throws when the browser blocks site data', () => {
    silenceConsole();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('TEST-blocked');
    });

    expect(() => recordAuthEvent('login', 'TEST-reason')).not.toThrow();
  });
});

describe('recording a page load', () => {
  /**
   * The origin only. The identity provider sends the code and state back in the query, and a path can
   * name a person: neither may be stored or printed. Which service the page came from is the whole
   * diagnostic value.
   */
  it('stores only the origin the page came from', () => {
    const info = silenceConsole();
    recordPageLoad('navigate', 'http://localhost:34567/u/TEST%40example.invalid/extra?code=TEST-secret#TEST-frag');

    const [entry] = readAuthLog();
    expect(entry.event).toBe('page load');
    expect(entry.detail).toBe('navigate from http://localhost:34567');
    const printed = JSON.stringify(info.mock.calls);
    for (const leak of ['TEST%40example.invalid', 'TEST-secret', 'TEST-frag']) {
      expect(sessionStorage.getItem(AUTH_LOG_KEY)).not.toContain(leak);
      expect(printed).not.toContain(leak);
    }
  });

  /**
   * An earlier build stored referrer paths under the old key. Those entries are deleted on load,
   * never read or printed again.
   */
  it('deletes the log an earlier build kept, without printing it', () => {
    const info = silenceConsole();
    sessionStorage.setItem(LEGACY_AUTH_LOG_KEY, JSON.stringify([{ at: 'TEST', event: 'page load', detail: 'TEST-old-path' }]));

    recordPageLoad('navigate', '');

    expect(sessionStorage.getItem(LEGACY_AUTH_LOG_KEY)).toBeNull();
    expect(JSON.stringify(info.mock.calls)).not.toContain('TEST-old-path');
    expect(AUTH_LOG_KEY).not.toBe(LEGACY_AUTH_LOG_KEY);
  });

  it('says so when the page came from nowhere', () => {
    silenceConsole();
    recordPageLoad('reload', '');

    expect(readAuthLog()[0].detail).toBe('reload from (none)');
  });

  it('prints the whole log so far, so a console opened after a reload still shows it', () => {
    const info = silenceConsole();
    recordAuthEvent('login', 'TEST-earlier');
    info.mockClear();

    recordPageLoad('navigate', '');

    expect(info).toHaveBeenCalledWith('[spire auth] recent events', expect.arrayContaining([
      expect.objectContaining({ detail: 'TEST-earlier' }),
    ]));
  });
});
