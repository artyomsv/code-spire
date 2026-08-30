import { FormEvent, useEffect, useState } from 'react';
import { Trash2, UsersRound } from 'lucide-react';
import {
  ObservedAuthor,
  OperatorIdentityLink,
  SeenOperator,
  fetchOperatorCandidates,
  fetchOperatorIdentities,
  fetchSeenOperators,
  linkOperatorIdentity,
  unlinkOperatorIdentity,
} from '../api';
import { ScmConnections } from './ScmConnections';

/**
 * Which SCM accounts belong to which operator (FR-11).
 *
 * <p>Two ways in, and the order on screen is the order of preference. An operator normally proves
 * their own account by signing into the platform — {@link ScmConnections} is what enables that, and
 * a proof needs no admin at all. The form below is the repair path: an operator who has left, an
 * account that was renamed, a platform with no sign-in application configured.
 *
 * <p><b>Both ends are picked, never typed.</b> The first version asked for an OIDC subject and a
 * stable provider id — two opaque values the product displays nowhere, so the only way to fill the
 * form was to query the database, while both had already been recorded by ordinary use: subjects by
 * every sign-in, author ids by every review.
 *
 * <p>Admin-only including the listing, because this is a map from named people to their measured
 * activity — ADR-022's "a listing is an inventory" rule at its sharpest.
 */
export function SettingsOperators() {
  const [links, setLinks] = useState<OperatorIdentityLink[] | null>(null);
  const [candidates, setCandidates] = useState<ObservedAuthor[]>([]);
  const [operators, setOperators] = useState<SeenOperator[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [subject, setSubject] = useState('');
  const [picked, setPicked] = useState('');
  const [saving, setSaving] = useState(false);

  const reload = () =>
    Promise.all([fetchOperatorIdentities(), fetchOperatorCandidates(), fetchSeenOperators()])
      .then(([linked, seen, people]) => {
        setLinks(linked);
        setCandidates(seen);
        setOperators(people);
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));

  useEffect(() => {
    void reload();
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    const author = candidates.find((c) => keyOf(c) === picked);
    if (!author) {
      setError('Pick the SCM account this operator uses.');
      return;
    }
    setSaving(true);
    try {
      await linkOperatorIdentity({
        oidcSubject: subject,
        providerType: author.providerType,
        authorId: author.authorId,
      });
      setSubject('');
      setPicked('');
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (link: OperatorIdentityLink) => {
    setError(null);
    try {
      await unlinkOperatorIdentity(link.oidcSubject, link.providerType);
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const nothingToPick = operators.length === 0 || candidates.length === 0;

  return (
    <section className="content">
      <ScmConnections />

      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <UsersRound size={15} className="an-title-icon" /> Operators
          </h2>
        </div>

        <p className="prov-note">
          Links an operator to the SCM accounts whose reviews are measured about them, so per-author
          analytics can show someone their own numbers. Prefer the sign-in above: it is the platform
          confirming the account rather than an assertion that two names refer to one person. Link by
          hand when that is not available — an operator who has left, or an account renamed.
        </p>

        <form onSubmit={submit} className="op-form">
          <label className="field">
            <span className="field-label">Operator</span>
            <select value={subject} onChange={(e) => setSubject(e.target.value)} required>
              <option value="">Choose someone who has signed in…</option>
              {operators.map((operator) => (
                <option key={operator.subject} value={operator.subject}>
                  {labelFor(operator)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">SCM account</span>
            <select value={picked} onChange={(e) => setPicked(e.target.value)} required>
              <option value="">Choose a reviewed author…</option>
              {candidates.map((c) => (
                <option key={keyOf(c)} value={keyOf(c)}>
                  {c.displayName} · {c.providerType} · {c.reviews}{' '}
                  {c.reviews === 1 ? 'review' : 'reviews'}
                </option>
              ))}
            </select>
          </label>
          <button className="btn" type="submit" disabled={saving || nothingToPick}>
            {saving ? 'Linking…' : 'Link'}
          </button>
        </form>

        {operators.length === 0 && (
          <p className="prov-note">
            Nobody has signed in yet, so there is nobody to link. An operator appears here the first
            time they open the dashboard.
          </p>
        )}
        {operators.length > 0 && candidates.length === 0 && (
          <p className="prov-note">
            No author has been reviewed yet, so there is no account to link to. Run a review first.
          </p>
        )}

        {error && (
          <p className="prov-note an-error" role="alert">
            {error}
          </p>
        )}

        {links !== null && links.length === 0 && (
          <p className="prov-note">No operator is linked yet, so nobody can see their own activity.</p>
        )}

        {links !== null && links.length > 0 && (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Operator</th>
                <th>Platform</th>
                <th>SCM account</th>
                <th className="cell-r" />
              </tr>
            </thead>
            <tbody>
              {links.map((link) => (
                <tr key={`${link.oidcSubject}-${link.providerType}`}>
                  <td>
                    <span className="prov-name">{operatorName(operators, link.oidcSubject)}</span>
                    <div className="prov-sub">{link.oidcSubject}</div>
                  </td>
                  <td>{link.providerType}</td>
                  <td>
                    <span className="prov-name">{nameFor(candidates, link)}</span>
                    <div className="prov-sub">{link.authorId}</div>
                  </td>
                  <td className="cell-r">
                    <button
                      className="iconbtn"
                      aria-label={`Unlink ${link.oidcSubject} on ${link.providerType}`}
                      onClick={() => void remove(link)}
                    >
                      <Trash2 size={15} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}

/**
 * The `<option>` value for one account.
 *
 * <p>A pipe, not a space or a colon: a Bitbucket author id is itself `557058:ee019d01-...`, so a
 * colon would make the split ambiguous. Nothing is ever parsed back out of this — the picked key is
 * matched against the candidate list — but an ambiguous key would still match the wrong candidate if
 * two ids ever differed only by where the separator fell.
 */
function keyOf(author: ObservedAuthor): string {
  return `${author.providerType}|${author.authorId}`;
}

/** Name first, username second, and the opaque subject only when there is nothing else to show. */
function labelFor(operator: SeenOperator): string {
  if (operator.displayName && operator.displayName !== operator.username) {
    return `${operator.displayName} · ${operator.username}`;
  }
  return operator.username || operator.subject;
}

function operatorName(operators: SeenOperator[], subject: string): string {
  const match = operators.find((o) => o.subject === subject);
  return match ? labelFor(match) : subject;
}

/** The display name the reviews recorded, falling back to the id when the author is gone. */
function nameFor(candidates: ObservedAuthor[], link: OperatorIdentityLink): string {
  const match = candidates.find(
    (c) => c.providerType === link.providerType && c.authorId === link.authorId,
  );
  return match ? match.displayName : link.authorId;
}
