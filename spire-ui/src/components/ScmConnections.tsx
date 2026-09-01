import { FormEvent, useEffect, useState } from 'react';
import { KeyRound, Trash2 } from 'lucide-react';
import { ScmOAuthApp, deleteScmOAuthApp, fetchScmOAuthApps, saveScmOAuthApp } from '../api';
import CopyField from './CopyField';
import { baseUrlHint, oauthSetupGuide } from './oauthAppSetup';

/**
 * The OAuth applications operators sign into to prove which SCM account is theirs.
 *
 * <p>This is what makes the mapping self-service. Without it an admin has to assert that a dashboard
 * operator and an SCM author are the same person, and that assertion cannot be checked: the bot's
 * token proves only the bot's identity, and matching usernames shows one person another person's
 * numbers whenever two names happen to agree.
 *
 * <p><b>The instructions are the feature.</b> Registering an application means working in the
 * platform's own portal, where every field is named differently and one wrong value fails with a
 * message that mentions nothing from this product. Settings → Webhooks already learned this and
 * ships a numbered checklist; this reuses that shape, and the same {@link CopyField}, so the value
 * that must match exactly is copied rather than retyped.
 */
export function ScmConnections() {
  const [apps, setApps] = useState<ScmOAuthApp[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const reload = () =>
    fetchScmOAuthApps()
      .then(setApps)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));

  useEffect(() => {
    void reload();
  }, []);

  const remove = async (providerType: string) => {
    setError(null);
    try {
      await deleteScmOAuthApp(providerType);
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="card">
      <div className="prov-head">
        <h2 className="prov-title">
          <KeyRound size={15} className="an-title-icon" /> Sign-in applications
        </h2>
      </div>

      <p className="prov-note">
        With one of these set up, an operator proves their own SCM account by signing in to it — the
        platform says who they are, so nobody has to assert it. The only permission requested is
        reading the signed-in account’s own profile, never repository access.
      </p>

      {/* The three facts everyone asks before touching the form, and the panel used to answer
          none of them. "Whose account" in particular is not guessable: the application is nothing
          to do with the bot credential, and it is not per person. */}
      <ul className="oauth-facts">
        <li>
          <strong>You register it once per platform</strong>, not once per person. Every operator
          signs in through the same application.
        </li>
        <li>
          <strong>Under whatever account owns your repositories</strong> — an organization, group or
          workspace if they are shared, or your own account if they are yours. Each platform’s exact
          place is named below.
        </li>
        <li>
          <strong>This is not the bot’s credential.</strong> That one proves the reviewer’s
          identity; this one lets a person prove their own.
        </li>
        <li>
          <strong>No tunnel needed, unlike a webhook.</strong> Nothing has to reach this deployment
          from the internet: the platform redirects the operator’s own browser to the address below,
          and this deployment calls out to the platform. A <code>localhost</code> address works, and
          all three platforms accept one.
        </li>
      </ul>

      {error && (
        <p className="prov-note an-error" role="alert">
          {error}
        </p>
      )}

      {apps.map((app) => (
        <AppRow
          key={app.providerType}
          app={app}
          open={open === app.providerType}
          onToggle={() => setOpen(open === app.providerType ? null : app.providerType)}
          onSaved={() => {
            setOpen(null);
            void reload();
          }}
          onDelete={() => void remove(app.providerType)}
          onError={setError}
        />
      ))}
    </div>
  );
}

function AppRow({
  app,
  open,
  onToggle,
  onSaved,
  onDelete,
  onError,
}: {
  app: ScmOAuthApp;
  open: boolean;
  onToggle: () => void;
  onSaved: () => void;
  onDelete: () => void;
  onError: (message: string) => void;
}) {
  const [clientId, setClientId] = useState(app.clientId);
  const [clientSecret, setClientSecret] = useState('');
  const [webBaseUrl, setWebBaseUrl] = useState(app.webBaseUrl ?? '');
  const [apiBaseUrl, setApiBaseUrl] = useState(app.apiBaseUrl ?? '');
  const [saving, setSaving] = useState(false);
  const guide = oauthSetupGuide(app.providerType);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    try {
      // clientSecret is sent as typed, and blank means KEEP. Sending '' would wipe a working
      // credential the moment somebody edited only a base URL.
      await saveScmOAuthApp({ providerType: app.providerType, webBaseUrl, apiBaseUrl, clientId, clientSecret });
      setClientSecret('');
      onSaved();
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="oauth-app">
      <div className="oauth-app-head">
        <span className="prov-name">{guide ? guide.providerLabel : app.providerType}</span>
        <span className={`badge ${app.hasSecret ? 'mem-active' : 'muted'}`}>
          {app.hasSecret ? 'Configured' : 'Not set up'}
        </span>
        <div className="grow" />
        <button className="btn-ghost" onClick={onToggle}>
          {open ? 'Close' : app.hasSecret ? 'Edit' : 'Set up'}
        </button>
        {app.hasSecret && (
          <button
            className="iconbtn"
            aria-label={`Remove the ${app.providerType} sign-in application`}
            onClick={onDelete}
          >
            <Trash2 size={15} />
          </button>
        )}
      </div>

      {open && (
        <div className="oauth-app-open">
          {/* First, because every step below refers to it and it is the one value an admin cannot
              work out: it depends on how this deployment is reached. */}
          <CopyField
            label="Redirect address to register"
            value={app.redirectUri}
            hint="Paste this into the application on the platform. It must match exactly — scheme, host, port and path — and it is whatever address your browser reached this page on, so it stays correct behind a proxy without a second setting."
          />

          {guide && <SetupChecklist providerType={app.providerType} />}

          <form onSubmit={submit} className="oauth-app-form">
            <label className="field">
              <span className="field-label">Client id</span>
              <input
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
                placeholder={placeholderFor(app.providerType, 'id')}
                required
              />
            </label>
            <label className="field">
              <span className="field-label">Client secret</span>
              <input
                type="password"
                value={clientSecret}
                onChange={(e) => setClientSecret(e.target.value)}
                placeholder={app.hasSecret ? 'leave blank to keep the stored one' : ''}
                required={!app.hasSecret}
              />
              {app.hasSecret && (
                <small className="field-hint">
                  Stored and never shown again. Leave blank unless you are replacing it.
                </small>
              )}
            </label>
            <label className="field">
              <span className="field-label">Sign-in base URL</span>
              <input
                value={webBaseUrl}
                onChange={(e) => setWebBaseUrl(e.target.value)}
                placeholder="hosted service"
              />
              <small className="field-hint">{baseUrlHint(app.providerType, 'web')}</small>
            </label>
            <label className="field">
              <span className="field-label">API base URL</span>
              <input
                value={apiBaseUrl}
                onChange={(e) => setApiBaseUrl(e.target.value)}
                placeholder="hosted service"
              />
              <small className="field-hint">{baseUrlHint(app.providerType, 'api')}</small>
            </label>
            <button className="btn" type="submit" disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </form>
        </div>
      )}
    </div>
  );
}

/** The platform's own portal, step by step — the same shape Settings → Webhooks uses. */
function SetupChecklist({ providerType }: { providerType: string }) {
  const guide = oauthSetupGuide(providerType);
  if (!guide) {
    return null;
  }
  return (
    <div className="wh-setup">
      <div className="wh-setup-title">On {guide.providerLabel} — one-off setup</div>
      {/* Above the steps, because "whose account" is asked before "what do I click". */}
      <div className="oauth-owner">
        <div className="oauth-owner-where">Register it under the account that owns the repositories</div>
        <dl className="oauth-owner-cases">
          <dt>Shared repositories</dt>
          <dd>{guide.owner.shared}</dd>
          <dt>Your own repositories</dt>
          <dd>{guide.owner.personal}</dd>
        </dl>
        <div className="wh-step-detail">{guide.owner.detail}</div>
      </div>
      <ol className="wh-steps">
        {guide.steps.map((step, i) => (
          <li className="wh-step" key={i}>
            <div className="wh-step-body">
              <div className="wh-step-title">{step.title}</div>
              {step.detail && <div className="wh-step-detail">{step.detail}</div>}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

/**
 * What the client id looks like on each platform.
 *
 * <p>Only a shape, never a value that could be mistaken for a real credential — the placeholders are
 * the platform's own field name rather than a plausible-looking id.
 */
function placeholderFor(providerType: string, field: 'id'): string {
  if (field !== 'id') {
    return '';
  }
  switch (providerType) {
    case 'bitbucket-cloud':
      return 'the consumer’s Key';
    case 'gitlab':
      return 'the Application ID';
    default:
      return 'the Client ID';
  }
}
