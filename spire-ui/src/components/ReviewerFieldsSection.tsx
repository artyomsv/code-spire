import Select from './Select';
import { conversationLabel } from './ProviderFormModal';
const CONVERSATION_OPTIONS = ['', 'REPORT_ONLY', 'EXPLAIN', 'INTERACTIVE'] as const;
export interface ReviewerFields { conversationLevel: string; }
export default function ReviewerFieldsSection({ reviewer, patchReviewer }: {
  reviewer: ReviewerFields; patchReviewer: (patch: Partial<ReviewerFields>) => void;
}) {
  return <label className="field">
    <span>Conversation level</span>
    <Select ariaLabel="Conversation level" value={reviewer.conversationLevel}
      options={CONVERSATION_OPTIONS.map(value => ({ value, label: conversationLabel(value) }))}
      onChange={conversationLevel => patchReviewer({ conversationLevel })} />
    <small className="field-hint">How deeply the bot converses in review threads. Inherit uses the global default.</small>
  </label>;
}