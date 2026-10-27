import type { WrittenPlan } from './workDraftApi';

interface Props { plan: WrittenPlan }

/**
 * The plan the factory wrote (M4 slice 3, §6.10): its steps in order, each with how anyone can tell it is done. A step
 * title is model output from a ticket anyone could write, so every text is shown as written, never set as HTML.
 */
export default function PlanView({ plan }: Props) {
  return <div>
    <p className="prov-sub">Plan — written by the factory · {plan.steps.length === 1 ? '1 step' : `${plan.steps.length} steps`} · digest <code>{plan.sha256.slice(0, 12)}</code></p>
    <ol aria-label="Plan steps">
      {plan.steps.map(step => <li key={step.id}>
        <strong>{step.title}</strong>
        <p className="prov-note">Done when: {step.doneWhen}</p>
      </li>)}
    </ol>
  </div>;
}
