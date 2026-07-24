import { useCallback, useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown } from 'lucide-react';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface SelectProps {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  ariaLabel?: string;
  id?: string;
  className?: string;
}

/**
 * On-brand accessible single-select. The trigger is a styled button; the listbox is portalled to
 * <body> and fixed-positioned from the trigger rect (like Tooltip), so it escapes modal/card overflow
 * clipping and can be wider than a narrow trigger. Focus stays on the trigger (aria-activedescendant
 * pattern); keyboard: arrows / Home / End / Enter / Escape / type-ahead. Outside-click + Escape close.
 */
export default function Select({
  value, options, onChange, placeholder, disabled, ariaLabel, id, className,
}: SelectProps) {
  const [open, setOpen] = useState(false);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const [active, setActive] = useState(-1);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const typeahead = useRef({ text: '', at: 0 });
  const baseId = useId();

  const selectedIndex = options.findIndex((o) => o.value === value);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;

  const place = useCallback(() => {
    const el = triggerRef.current;
    if (el) setRect(el.getBoundingClientRect());
  }, []);

  // next enabled index from `from` in direction dir, wrapping
  const step = useCallback((from: number, dir: 1 | -1) => {
    const n = options.length;
    for (let k = 1; k <= n; k += 1) {
      const i = (from + dir * k + n) % n;
      if (!options[i].disabled) return i;
    }
    return from;
  }, [options]);

  const openAt = useCallback((startAt: number) => {
    if (disabled) return;
    place();
    setActive(startAt);
    setOpen(true);
  }, [disabled, place]);

  const close = useCallback((focusTrigger = true) => {
    setOpen(false);
    setActive(-1);
    if (focusTrigger) triggerRef.current?.focus();
  }, []);

  const choose = useCallback((i: number) => {
    const opt = options[i];
    if (!opt || opt.disabled) return;
    onChange(opt.value);
    close();
  }, [options, onChange, close]);

  useEffect(() => {
    if (!open) return undefined;
    const reposition = () => place();
    const onPointer = (e: PointerEvent) => {
      const t = e.target as Node;
      if (triggerRef.current?.contains(t) || listRef.current?.contains(t)) return;
      setOpen(false);
      setActive(-1);
    };
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    document.addEventListener('pointerdown', onPointer, true);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
      document.removeEventListener('pointerdown', onPointer, true);
    };
  }, [open, place]);

  useEffect(() => {
    if (open && active >= 0) {
      const el = document.getElementById(`${baseId}-opt-${active}`);
      el?.scrollIntoView?.({ block: 'nearest' });
    }
  }, [open, active, baseId]);

  const openStart = () => (selectedIndex >= 0 ? selectedIndex : step(-1, 1));

  function onKeyDown(e: React.KeyboardEvent) {
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openAt(openStart());
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        openAt(selectedIndex >= 0 ? selectedIndex : step(0, -1));
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); setActive((i) => step(i < 0 ? -1 : i, 1)); break;
      case 'ArrowUp': e.preventDefault(); setActive((i) => step(i < 0 ? 0 : i, -1)); break;
      case 'Home': e.preventDefault(); setActive(step(-1, 1)); break;
      case 'End': e.preventDefault(); setActive(step(0, -1)); break;
      case 'Enter': e.preventDefault(); if (active >= 0) choose(active); break;
      case 'Escape': e.preventDefault(); close(); break;
      case 'Tab': close(false); break;
      default:
        if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
          const now = Date.now();
          const text = now - typeahead.current.at < 600 ? typeahead.current.text + e.key : e.key;
          typeahead.current = { text, at: now };
          const lower = text.toLowerCase();
          const match = options.findIndex((o) => !o.disabled && o.label.toLowerCase().startsWith(lower));
          if (match >= 0) setActive(match);
        }
    }
  }

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        id={id}
        className={className ? `select ${className}` : 'select'}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${baseId}-list` : undefined}
        aria-activedescendant={open && active >= 0 ? `${baseId}-opt-${active}` : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => (open ? close() : openAt(openStart()))}
        onKeyDown={onKeyDown}
      >
        <span className={selected ? 'select-val' : 'select-val placeholder'} title={selected?.label}>
          {selected ? selected.label : placeholder ?? ''}
        </span>
        <ChevronDown className="select-chev" size={15} aria-hidden="true" />
      </button>
      {open && rect
        && createPortal(
          <ul ref={listRef} id={`${baseId}-list`} role="listbox" className="select-pop" style={popStyle(rect)}>
            {options.map((o, i) => (
              <li
                key={o.value}
                id={`${baseId}-opt-${i}`}
                role="option"
                aria-selected={o.value === value}
                aria-disabled={o.disabled || undefined}
                className={`select-opt${i === active ? ' active' : ''}${o.disabled ? ' disabled' : ''}`}
                title={o.label}
                onMouseEnter={() => { if (!o.disabled) setActive(i); }}
                onClick={() => choose(i)}
              >
                {o.label}
              </li>
            ))}
          </ul>,
          document.body,
        )}
    </>
  );
}

/** Fixed-position from the trigger rect; flips above when there's more room up top. */
function popStyle(rect: DOMRect): CSSProperties {
  const maxH = 280;
  const below = window.innerHeight - rect.bottom;
  const flip = below < Math.min(maxH, 200) && rect.top > below;
  return {
    position: 'fixed',
    left: rect.left,
    minWidth: rect.width,
    maxWidth: Math.min(380, window.innerWidth - 16),
    maxHeight: maxH,
    ...(flip ? { bottom: window.innerHeight - rect.top + 4 } : { top: rect.bottom + 4 }),
  };
}
