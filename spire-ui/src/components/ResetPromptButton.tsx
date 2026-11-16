import { useState } from 'react';
import { RotateCcw } from 'lucide-react';
import ConfirmDialog from './ConfirmDialog';

interface Props {
  promptName: string;
  disabled: boolean;
  onReset: () => Promise<void>;
}

/**
 * Reset to default, behind a yes: a reset deletes the custom text saved for this scope, and nothing keeps a copy.
 * The drift banner's "Take the new default" stays one click, because that banner already shows both texts side by side.
 */
export default function ResetPromptButton({ promptName, disabled, onReset }: Props) {
  const [asking, setAsking] = useState(false);
  return (
    <>
      <button type="button" className="btn-ghost" disabled={disabled} onClick={() => setAsking(true)}>
        <RotateCcw size={14} aria-hidden="true" /> Reset to default
      </button>
      {asking && (
        <ConfirmDialog
          title="Reset prompt"
          message={`Reset ${promptName} to its default? The custom text saved for this scope is deleted and cannot be restored.`}
          confirmLabel="Reset"
          busyLabel="Resetting…"
          danger
          onConfirm={onReset}
          onClose={() => setAsking(false)}
        />
      )}
    </>
  );
}
