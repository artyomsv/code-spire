import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import RunCard from './RunCard';

export default function RunTaskCard({ summary }: { summary: string | null }) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function copy() {
    try {
      await navigator.clipboard.writeText(summary ?? '');
      setCopied(true);
      setError(null);
    } catch {
      setError('Could not copy the task. Select the text to copy it.');
    }
  }
  return <RunCard title="Task">
    <p className="run-task">{summary ?? 'not recorded'}</p>
    {summary && <button className="copy-btn" type="button" onClick={() => void copy()}>
      {copied ? <Check size={14} /> : <Copy size={14} />}{copied ? 'Copied' : 'Copy'}
    </button>}
    {error && <p role="alert" className="prov-sub">{error}</p>}
  </RunCard>;
}
