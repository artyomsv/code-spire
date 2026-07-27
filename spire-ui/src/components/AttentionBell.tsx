import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Bell, CircleAlert, TriangleAlert } from 'lucide-react';
import { useAttention } from '../hooks/useAttention';
import Tooltip from './Tooltip';

/**
 * Where a row's link goes, as its visible text. Every row used to read "Settings", which told the
 * operator nothing about where they were about to land and left several rows' links
 * indistinguishable from each other — including to a screen reader, which announces link text.
 */
const ACTION_LABELS: Record<string, string> = {
  '/settings/webhooks': 'Settings · Webhooks',
  '/settings/providers': 'Settings · Providers',
  '/settings/llm': 'Settings · LLM',
  '/settings/context': 'Settings · Context',
  '/settings/dlq': 'Dead-letter',
  '/': 'Reviews',
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? 'Open';
}

/**
 * Conditions needing the operator's attention. There is no dismiss action anywhere: every row
 * is a query result, so fixing the cause is what removes it. Zero conditions renders NO badge
 * rather than a green tick — the panel only knows about conditions it checks, so "all clear"
 * would be a claim it cannot make.
 */
export default function AttentionBell() {
  const { items } = useAttention();
  const [open, setOpen] = useState(false);

  const blocking = items.some((item) => item.severity === 'BLOCKING');
  const tone = blocking ? 'blocking' : 'warning';
  // The accessible name must carry the same information the badge carries visually — the count,
  // not just the fact — so a screen-reader user isn't told the opposite of what's on screen.
  const attentionLabel =
    items.length === 0
      ? 'Nothing needs attention'
      : items.length === 1
        ? '1 condition needs attention'
        : `${items.length} conditions need attention`;

  return (
    <div className="attention">
      <Tooltip label={items.length === 0 ? 'Nothing needs attention' : 'Needs attention'}>
        <button
          className="iconbtn"
          data-testid="attention-toggle"
          aria-label={attentionLabel}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Bell size={17} />
          {items.length > 0 && (
            <span className={`attention-count ${tone}`} data-testid="attention-count">
              {items.length}
            </span>
          )}
        </button>
      </Tooltip>

      {open && (
        <div className="attention-panel" role="dialog" aria-label="Needs attention">
          {items.length === 0 ? (
            <p className="attention-empty">No conditions need attention.</p>
          ) : (
            <ul className="attention-list">
              {items.map((item) => (
                <li key={`${item.code}:${item.subject ?? ''}:${item.action ?? ''}`} className={`attention-row ${item.severity.toLowerCase()}`}>
                  <span className="attention-icon">
                    {item.severity === 'BLOCKING' ? <CircleAlert size={15} /> : <TriangleAlert size={15} />}
                  </span>
                  <span className="attention-body">
                    {item.subject && <span className="attention-subject">{item.subject}</span>}
                    <span className="attention-message">{item.message}</span>
                    {item.action && (
                      <Link className="attention-action" to={item.action} onClick={() => setOpen(false)}>
                        {actionLabel(item.action)}
                      </Link>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
