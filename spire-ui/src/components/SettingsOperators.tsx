import { FormEvent, useEffect, useState } from 'react';
import { Trash2, UsersRound } from 'lucide-react';
import {
  OperatorIdentityLink,
  fetchOperatorIdentities,
  linkOperatorIdentity,
  unlinkOperatorIdentity,
} from '../api';

/**
 * Linking an operator to the SCM account whose reviews are measured about them.
 *
 * <p>Admin-only, including the listing, because this is a map from named people to
 * their measured activity — ADR-022's "a listing is an inventory" rule at its sharpest.
 *
 * <p>There is no "match by username" button and there will not be one: a coincidental
 * match between an OIDC username and an SCM handle would show one person another
 * person's performance data, and nothing on the screen would look wrong.
 */
export function SettingsOperators() {
  const [links, setLinks] = useState<OperatorIdentityLink[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [subject, setSubject] = useState('');
  const [providerType, setProviderType] = useState('github');
  const [authorId, setAuthorId] = useState('');
  const [saving, setSaving] = useState(false);

  const reload = () =>
    fetchOperatorIdentities()
      .then(setLinks)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));

  useEffect(() => {
    void reload();
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSaving(true);
    try {
      await linkOperatorIdentity({ oidcSubject: subject.trim(), providerType, authorId: authorId.trim() });
      setSubject('');
      setAuthorId('');
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
    <section className="card">
      <h2>
        <UsersRound size={16} /> Operators
      </h2>
      <p className="muted">
        Links a signed-in operator to their SCM account, so per-author analytics can show someone
        their own numbers. An operator finds their operator id on their own activity screen and
        gives it to you — it is never guessed from a username, because a wrong guess would show one
        person another person’s data with nothing on screen looking wrong.
      </p>

      <form onSubmit={submit} className="form-row">
        <label>
          Operator id
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="from the operator’s activity screen"
            required
          />
        </label>
        <label>
          Platform
          <select value={providerType} onChange={(e) => setProviderType(e.target.value)}>
            <option value="github">github</option>
            <option value="gitlab">gitlab</option>
            <option value="bitbucket-cloud">bitbucket-cloud</option>
          </select>
        </label>
        <label>
          SCM user id
          <input
            value={authorId}
            onChange={(e) => setAuthorId(e.target.value)}
            placeholder="stable id, not the display name"
            required
          />
        </label>
        <button type="submit" disabled={saving}>
          {saving ? 'Linking…' : 'Link'}
        </button>
      </form>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {links !== null && links.length === 0 && (
        <p className="muted">No operator is linked yet, so nobody can see their own activity.</p>
      )}

      {links !== null && links.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Operator id</th>
              <th>Platform</th>
              <th>SCM user id</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {links.map((link) => (
              <tr key={`${link.oidcSubject}-${link.providerType}`}>
                <td>
                  <code>{link.oidcSubject}</code>
                </td>
                <td>{link.providerType}</td>
                <td>{link.authorId}</td>
                <td>
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
    </section>
  );
}
