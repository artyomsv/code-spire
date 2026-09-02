import { useEffect, useRef, useState } from 'react';
import { Check, Copy } from 'lucide-react';

/**
 * A read-only value with an always-visible, labelled Copy button that confirms on click.
 *
 * <p>Shared, because two screens hand the operator a value that has to be pasted into someone
 * else's portal exactly — a webhook's payload URL and secret, and an OAuth application's redirect
 * address. In both cases a value retyped with one character wrong fails on the platform's side with
 * a message that names nothing in this product, so "copy" is the affordance that matters and it
 * should behave identically in both places.
 */
export default function CopyField({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  function copy() {
    void navigator.clipboard?.writeText(value);
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1400);
  }

  return (
    <div className="field">
      <span>{label}</span>
      <div className="reveal-value">
        <span className="mono">{value}</span>
        <button type="button" className={`copy-btn ${copied ? 'copied' : ''}`} onClick={copy}>
          {copied ? <Check size={14} /> : <Copy size={14} />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      {hint && <small className="field-hint">{hint}</small>}
    </div>
  );
}
