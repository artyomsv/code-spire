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

/** Half the CSS `max-width` of `.tooltip`, plus its margin — the most the bubble can extend either side
 *  of its centre, so the centre is kept at least this far from both edges. */
const TOOLTIP_HALF_WIDTH = 182;

/** Keep a bubble centre inside the viewport; jsdom and SSR (no window) pass the value through. */
export function clampToViewport(centre: number, viewportWidth = typeof window === 'undefined' ? 0 : window.innerWidth) {
  if (viewportWidth <= TOOLTIP_HALF_WIDTH * 2) {
    return centre; // too narrow to satisfy both edges — CSS max-width already caps the bubble
  }
  return Math.min(Math.max(centre, TOOLTIP_HALF_WIDTH), viewportWidth - TOOLTIP_HALF_WIDTH);
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
            // Centred on the trigger, but kept inside the viewport: the bubble is translateX(-50%), so
            // a trigger near either edge would otherwise push half of a wide bubble off-screen.
            style={{ left: clampToViewport(rect.left + rect.width / 2), top: rect.bottom + 8 }}
          >
            {label}
          </span>,
          document.body,
        )}
    </span>
  );
}
