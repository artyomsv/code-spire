import type { WebhookRepoSecret } from '../../api';
import CopyField from '../CopyField';
import { webhookPath } from '../SettingsWebhookRepos';

/** What the forge must send to each kind of hook. A hook subscribed to the wrong events stays silent. */
const EVENTS: Record<string, string> = {
  ISSUE: 'In the forge webhook settings, subscribe to Issues and Issue comments.',
};

/**
 * The one moment a webhook secret is visible. It is minted by the gateway and never returned again,
 * so this stays centred and modal: dismissing it by accident means rotating the secret.
 */
export default function WebhookSecretReveal({ revealed, onDone }: { revealed: WebhookRepoSecret; onDone: () => void }) {
  const events = revealed.repo.eventKind ? EVENTS[revealed.repo.eventKind] : undefined;
  return <div className="modal-overlay"><div className="modal" role="dialog" aria-modal="true" aria-label="Webhook secret">
    <div className="modal-head"><h3>Webhook secret</h3></div><div className="modal-body">
      <p>Copy this secret now. It is shown once; store it in the forge webhook settings.</p>
      <CopyField label="Secret" value={revealed.secret} />
      <CopyField label="Webhook path" value={webhookPath(revealed.repo)} hint="Prefix with your public webhook base." />
      {events && <p>{events}</p>}
      <div className="modal-actions"><button className="btn" onClick={onDone}>Done</button></div>
    </div>
  </div></div>;
}
