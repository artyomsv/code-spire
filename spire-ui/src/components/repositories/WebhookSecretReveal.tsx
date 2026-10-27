import type { WebhookRepoSecret } from '../../api';
import CopyField from '../CopyField';
import WebhookSetupChecklist from '../WebhookSetupChecklist';
import { webhookPath } from '../SettingsWebhookRepos';

/**
 * The one moment a webhook secret is visible. It is minted by the gateway and never returned again,
 * so this stays centred and modal: dismissing it by accident means rotating the secret. The portal
 * steps follow the two values, with the events the created kind needs.
 */
export default function WebhookSecretReveal({ revealed, onDone }: { revealed: WebhookRepoSecret; onDone: () => void }) {
  return <div className="modal-overlay"><div className="modal wide" role="dialog" aria-modal="true" aria-label="Webhook secret">
    <div className="modal-head"><h3>Webhook secret</h3></div><div className="modal-body scroll">
      <p>Copy this secret now. It is shown once; store it in the forge webhook settings.</p>
      <CopyField label="Secret" value={revealed.secret} />
      <CopyField label="Webhook path" value={webhookPath(revealed.repo)} hint="Prefix with your public webhook base." />
      <WebhookSetupChecklist providerType={revealed.repo.providerType} eventKind={revealed.repo.eventKind} />
      <div className="modal-actions"><button className="btn" onClick={onDone}>Done</button></div>
    </div>
  </div></div>;
}
