import { useCallback, useState, type ReactElement } from 'react';
import { createPortal } from 'react-dom';

/**
 * Lightweight non-native tooltip. Wraps a single trigger element and shows a
 * themed bubble below it on hover/focus. The bubble is portalled to <body> and
 * fixed-positioned from the trigger's rect, so it escapes the `overflow: hidden`
 * on cards/tables that would clip a plain CSS `::after` tooltip.
 */
interface Props {
  label: string;
  children: ReactElement;
  /** Extra class on the wrapper (e.g. layout helpers like `tt-push`). */
  className?: string;
}

export default function Tooltip({ label, children, className }: Props) {
  const [rect, setRect] = useState<DOMRect | null>(null);

  const open = useCallback((el: HTMLElement) => setRect(el.getBoundingClientRect()), []);
  const close = useCallback(() => setRect(null), []);

  return (
    <span
      className={className ? `tt ${className}` : 'tt'}
      onMouseEnter={(e) => open(e.currentTarget)}
      onMouseLeave={close}
      onFocusCapture={(e) => open(e.currentTarget)}
      onBlurCapture={close}
    >
      {children}
      {rect &&
        createPortal(
          <span
            role="tooltip"
            className="tooltip"
            style={{ left: rect.left + rect.width / 2, top: rect.bottom + 8 }}
          >
            {label}
          </span>,
          document.body,
        )}
    </span>
  );
}
