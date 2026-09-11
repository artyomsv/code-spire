import { type AuthKind, type ProviderRole, type ProviderView } from '../api';
import Select from './Select';
const BEARER_ONLY = new Set(['github', 'gitlab']);
export interface AccountFields {
  name: string; type: string; baseUrl: string; workspace: string; authKind: AuthKind;
  authUsername: string; secret: string; botAccountId: string; enabled: boolean;
}
export default function AccountCredentialFields({ fields, patch, initial, role }: {
  fields: AccountFields; patch: (next: Partial<AccountFields>) => void; initial: ProviderView | null; role: ProviderRole;
}) {
  const { type, authKind, authUsername, secret, botAccountId } = fields;
  const editing = initial !== null;
  return <>
          <div className="field-row-13">
            <label className="field">
              <span>Auth kind</span>
              <Select
                ariaLabel="Auth kind"
                value={authKind}
                disabled={BEARER_ONLY.has(type)}
                options={[
                  { value: 'bearer', label: 'bearer' },
                  ...(BEARER_ONLY.has(type) ? [] : [{ value: 'basic', label: 'basic' }]),
                ]}
                onChange={(v) => patch({ authKind: v as AuthKind })}
              />
            </label>
            <label className="field">
              <span>Secret / token</span>
              <input
                type="password"
                autoComplete="new-password"
                placeholder={editing ? 'leave blank to keep current' : 'access token'}
                value={secret}
                onChange={(e) => patch({ secret: e.target.value })}
              />
              {editing && (
                <small className="field-hint">
                  {initial?.hasSecret ? 'A token is stored — leave blank to keep it.' : 'No token stored yet.'}
                </small>
              )}
            </label>
          </div>

          {authKind === 'basic' && (
            <label className="field">
              <span>Username</span>
              <input
                className="mono"
                placeholder="username"
                value={authUsername}
                onChange={(e) => patch({ authUsername: e.target.value })}
              />
            </label>
          )}

          <label className="field">
            <span>Bot account id <span className="field-optional">optional</span></span>
            <input
              className="mono"
              placeholder="auto-detected from the token"
              value={botAccountId}
              onChange={(e) => patch({ botAccountId: e.target.value })}
            />
            <small className="field-hint">
              Leave blank — it's resolved from the token when you save (which also validates the token).
              A Bitbucket workspace/repo access token has no user account, so it stays blank; the token
              is still validated against the workspace.
              {role === 'FACTORY' && (
                <>
                  {' '}
                  A Factory account must resolve to a login: a push is authenticated as one, and a workspace
                  access token with no user cannot push.
                </>
              )}
            </small>
          </label>


  </>;
}
