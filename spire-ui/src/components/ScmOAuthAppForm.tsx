import { FormEvent, useState } from 'react';
import { ScmOAuthApp, saveScmOAuthApp } from '../api';
import { baseUrlHint } from './oauthAppSetup';

/**
 * The form of one platform. Mounted only while its row is open, so its fields start from the row as last read, not as
 * it was when the page first drew it.
 */
export function ScmOAuthAppForm({ app, onSaved, onError }: { app: ScmOAuthApp; onSaved: () => void; onError: (message: string) => void }) {
  const [clientId, setClientId] = useState(app.clientId);
  const [clientSecret, setClientSecret] = useState('');
  const [webBaseUrl, setWebBaseUrl] = useState(app.webBaseUrl ?? '');
  const [apiBaseUrl, setApiBaseUrl] = useState(app.apiBaseUrl ?? '');
  const [saving, setSaving] = useState(false);

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
