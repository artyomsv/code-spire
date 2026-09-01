import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AnalyticsOverview, MyAnalytics } from './Analytics';
import * as api from '../api';

/**
 * The analytics screens (P4 / FR-11).
 *
 * <p>The state these tests care most about is <b>unlinked</b>. It is a third thing beside "empty"
 * and "error", and rendering it as an empty chart tells an operator they have done nothing when the
 * truth is that nobody knows who they are. That substitution — a case the code does not know about
 * falling through into the reassuring branch — is exactly the shape of the ADR-025 `refused`
 * incident, where a refused review rendered as five green segments under "done".
 */

const EMPTY_TOTALS = {
  findings: 0,
  judged: 0,
  dismissed: 0,
  resolved: 0,
  dismissalRate: null,
  medianRoundsToResolve: null,
  reviews: 0,
  suppressed: 0,
};

describe('MyAnalytics', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('says the identity is not linked rather than showing an empty chart', async () => {
    vi.spyOn(api, 'fetchMyActivity').mockResolvedValue({
      linked: false,
      identities: [],
      totals: null,
      breakdown: [],
    });

    render(
      <MemoryRouter>
        <MyAnalytics subject="TEST-SUBJECT-1" />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('status')).toBeTruthy());
    expect(screen.getByRole('status').textContent).toMatch(/isn’t linked/i);
    // The operator id has to be on screen, or the admin cannot be given the value to link.
    expect(screen.getByText('TEST-SUBJECT-1')).toBeTruthy();
  });

  it('shows which SCM account it is reporting on once linked', async () => {
    vi.spyOn(api, 'fetchMyActivity').mockResolvedValue({
      linked: true,
      identities: [{ oidcSubject: 'TEST-SUBJECT-1', providerType: 'github', authorId: 'TEST-AUTHOR-9' }],
      totals: { ...EMPTY_TOTALS, findings: 4, judged: 2, dismissed: 1, dismissalRate: 0.5 },
      breakdown: [
        { severity: 'NIT', category: 'NAMING', raised: 3, dismissed: 1, resolved: 1, unjudged: 1 },
      ],
    });

    render(
      <MemoryRouter>
        <MyAnalytics />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/github/)).toBeTruthy());
    expect(screen.getByText('NAMING')).toBeTruthy();
    expect(screen.getByText('50%')).toBeTruthy();
  });

  /** An error must not read as "no activity" either — three states, three sentences. */
  it('reports a failed load as an error rather than as no activity', async () => {
    vi.spyOn(api, 'fetchMyActivity').mockRejectedValue(new Error('TEST-BOOM'));

    render(
      <MemoryRouter>
        <MyAnalytics />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toMatch(/TEST-BOOM/);
  });
});

describe('AnalyticsOverview', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * A dismissal rate of 0% asserts "this team dismisses nothing", which is a claim about them. Until
   * something has been judged the honest answer is that we do not know — the same reason the verdict
   * column is nullable rather than defaulted.
   */
  it('renders an em dash rather than 0% when nothing has been judged', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: EMPTY_TOTALS,
      breakdown: [],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/nothing judged yet/i)).toBeTruthy());
    expect(screen.queryByText('0%')).toBeNull();
    // Two em dashes: the rate and the median. Both are absences, and neither is a zero.
    expect(screen.getAllByText('—').length).toBe(2);
  });

  it('explains an empty corpus instead of implying the reviewer found nothing', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: EMPTY_TOTALS,
      breakdown: [],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/No findings recorded yet/i)).toBeTruthy());
    // The confusion this caused in practice: a deployment with dozens of reviews, findings COUNTS
    // visible beside each one, and this reading zero. Saying only "nothing was backfilled" invited
    // the wrong conclusion — that the data was lost. It is not. Each review still holds a round of
    // findings; that round is refused because it is a snapshot with no verdicts, not history.
    expect(screen.getByText(/single overwritten round/i)).toBeTruthy();
    expect(screen.getByText(/no verdicts/i)).toBeTruthy();
  });

  /**
   * The tile counts DISTINCT review ids in the findings table, so it means "reviews that recorded
   * findings" and not "reviews this author opened". Labelled plain "Reviews" the number was right
   * and the label was a promise it never made, which reads as data loss rather than an empty corpus.
   */
  it('says the review count is of reviews that recorded findings', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: EMPTY_TOTALS,
      breakdown: [],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/Reviews with findings/i)).toBeTruthy());
  });

  /**
   * The spec says the suppression count appears on the summary comment AND the dashboard. Only the
   * comment was asserted. If this tile is missing, an operator reading the dashboard cannot tell a
   * quiet repository from one where a learned preference is hiding things.
   */
  it('shows how many findings a learned preference hid', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: { ...EMPTY_TOTALS, findings: 9, suppressed: 4 },
      breakdown: [],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/Hidden by preferences/i)).toBeTruthy());
    expect(screen.getByText('4')).toBeTruthy();
  });

  /** With nothing hidden the tile stays away, rather than reading a reassuring zero. */
  it('omits the hidden tile when no preference hid anything', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: EMPTY_TOTALS,
      breakdown: [],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(/No findings recorded yet/i)).toBeTruthy());
    expect(screen.queryByText(/Hidden by preferences/i)).toBeNull();
  });

  it('labels an uncategorized finding rather than leaving the cell blank', async () => {
    vi.spyOn(api, 'fetchAnalyticsOverview').mockResolvedValue({
      totals: { ...EMPTY_TOTALS, findings: 1 },
      breakdown: [
        { severity: 'MAJOR', category: null, raised: 1, dismissed: 0, resolved: 0, unjudged: 1 },
      ],
    });
    vi.spyOn(api, 'fetchAnalyticsRepos').mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AnalyticsOverview />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText('unlabelled')).toBeTruthy());
  });
});
