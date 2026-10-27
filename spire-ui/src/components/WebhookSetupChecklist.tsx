import { Check } from 'lucide-react';
import type { WebhookEventKind } from '../api';
import { webhookProviderLabel, webhookSetupGuide } from './webhookSetup';

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
 * events are the step that matters most. After a rotation the steps would lead to a second forge
 * webhook on the same key, whose old twin then fails every signature check, so a rotation gets one
 * line instead.
 */
export default function WebhookSetupChecklist({ providerType, eventKind, rotated }: Props) {
  if (rotated) {
    return <RotationNote providerType={providerType} />;
  }
  const guide = webhookSetupGuide(providerType, eventKind);
  if (!guide) {
    return null;
  }
  return (
    <div className="wh-setup">
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

function RotationNote({ providerType }: { providerType: string }) {
  const label = webhookProviderLabel(providerType);
  if (!label) {
    return null;
  }
  return (
    <div className="wh-setup">
      <div className="wh-setup-title">Next — on {label}</div>
      <div className="wh-step-detail">
        Open the existing webhook on {label}, replace its secret with the new one, and save. The URL and the events stay as they are.
      </div>
    </div>
  );
}
