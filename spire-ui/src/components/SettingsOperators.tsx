import { FormEvent, useEffect, useState } from 'react';
import { Trash2, UsersRound } from 'lucide-react';
import {
  ObservedAuthor,
  OperatorIdentityLink,
  fetchOperatorCandidates,
  fetchOperatorIdentities,
  linkOperatorIdentity,
  unlinkOperatorIdentity,
} from '../api';

/**
 * Linking an operator to the SCM accounts whose reviews are measured about them.
 *
 * <p>Admin-only including the listing, because this is a map from named people to their measured
 * activity — ADR-022's "a listing is an inventory" rule at its sharpest.
 *
 * <p><b>The SCM side is picked, never typed.</b> The first version asked an admin to enter a stable
 * provider id such as `3218389`; the product displays that value nowhere, so the field could only be
 * filled by someone willing to query the database — while every one of those ids had already been
 * recorded, dozens of times, by the reviews themselves. It is still an admin who decides WHICH author
 * is which operator: a coincidental username match would show one person another person's data with
 * nothing on screen looking wrong, so a human asserts the link and the product supplies the choices.
 */
export function SettingsOperators() {
  const [links, setLinks] = useState<OperatorIdentityLink[] | null>(null);
  const [candidates, setCandidates] = useState<ObservedAuthor[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [subject, setSubject] = useState('');
  const [picked, setPicked] = useState('');
  const [saving, setSaving] = useState(false);

  const reload = () =>
    Promise.all([fetchOperatorIdentities(), fetchOperatorCandidates()])
      .then(([linked, seen]) => {
        setLinks(linked);
        setCandidates(seen);
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
        oidcSubject: subject.trim(),
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

  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <UsersRound size={15} className="an-title-icon" /> Operators
          </h2>
        </div>

        <p className="prov-note">
          Links a signed-in operator to the SCM accounts whose reviews are measured about them, so
          per-author analytics can show someone their own numbers. The accounts below are the ones this
          deployment has actually reviewed — an operator can own several, and each is linked
          separately. An operator finds their operator id on their own activity screen.
        </p>

        <form onSubmit={submit} className="op-form">
          <label className="field">
            <span className="field-label">Operator id</span>
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="from the operator’s activity screen"
              required
            />
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
          <button className="btn" type="submit" disabled={saving || candidates.length === 0}>
            {saving ? 'Linking…' : 'Link'}
          </button>
        </form>

        {candidates.length === 0 && (
          <p className="prov-note">
            No author has been reviewed yet, so there is nobody to link. Run a review first.
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
                <th>Operator id</th>
                <th>Platform</th>
                <th>SCM account</th>
                <th className="cell-r" />
              </tr>
            </thead>
            <tbody>
              {links.map((link) => (
                <tr key={`${link.oidcSubject}-${link.providerType}`}>
                  <td>
                    <code>{link.oidcSubject}</code>
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
 * <p>A pipe, not a space or a colon: a Bitbucket author id is itself
 * `557058:ee019d01-863e-...`, so a colon would make the split ambiguous. Nothing is ever parsed back
 * out of this — the picked key is matched against the candidate list — but an ambiguous key would
 * still match the wrong candidate if two ids ever differed only by where the separator fell.
 */
function keyOf(author: ObservedAuthor): string {
  return `${author.providerType}|${author.authorId}`;
}

/** The display name the reviews recorded, falling back to the id when the author is gone. */
function nameFor(candidates: ObservedAuthor[], link: OperatorIdentityLink): string {
  const match = candidates.find(
    (c) => c.providerType === link.providerType && c.authorId === link.authorId,
  );
  return match ? match.displayName : link.authorId;
}
