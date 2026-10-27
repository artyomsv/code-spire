import { Check } from 'lucide-react';
import type { WebhookEventKind } from '../api';
import { webhookSetupGuide } from './webhookSetup';

interface Props {
  providerType: string;
  eventKind?: WebhookEventKind;
  /** The secret was rotated: the forge webhook exists, so only its secret changes. */
  rotated?: boolean;
}

/**
 * The forge's own portal, step by step, for one webhook kind. Shown wherever a secret is revealed,
 * because that is the moment the operator has the two values the portal asks for and is about to
 * leave this product to paste them. A hook subscribed to the wrong events stays silent, so the
 * events are the step that matters most. After a rotation the forge webhook usually exists, and the
 * steps would lead to a second one on the same key whose old twin then fails every signature check;
 * but a rotation is also how a lost create response is recovered, before any forge webhook exists.
 * So a rotation gets a note that names both cases, and keeps the steps.
 */
export default function WebhookSetupChecklist({ providerType, eventKind, rotated }: Props) {
  const guide = webhookSetupGuide(providerType, eventKind);
  if (!guide) {
    return null;
  }
  return (
    <div className="wh-setup">
      {rotated && <RotationNote providerLabel={guide.providerLabel} />}
      <div className="wh-setup-title">Next — set up on {guide.providerLabel}</div>
      <ol className="wh-steps">
        {guide.steps.map((step, i) => (
          <li className="wh-step" key={i}>
            <div className="wh-step-body">
              <div className="wh-step-title">{step.title}</div>
              {step.detail && <div className="wh-step-detail">{step.detail}</div>}
              {step.events && (
                <div className="chips">
                  {step.events.map((event) => (
                    <span className="chip on" key={event}>
                      <Check size={10} aria-hidden="true" /> {event}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function RotationNote({ providerLabel }: { providerLabel: string }) {
  return (
    <div className="wh-step-detail">
      If the webhook already exists on {providerLabel}, open it, replace its secret with the new one, and save; the URL and the
      events stay as they are. If you have not created it yet, follow the steps below.
    </div>
  );
}
