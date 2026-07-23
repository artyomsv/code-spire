import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  type TextareaHTMLAttributes,
} from 'react';

interface AutoTextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  value: string;
}

/**
 * A controlled textarea that grows to fit its content instead of scrolling. Height is recomputed
 * on every value change (typing and programmatic edits) and on window resize (text re-wraps at a
 * new width). The `rows` attribute still acts as the minimum height: resetting to `height:auto`
 * before measuring reverts the box to its rows-based size, so `scrollHeight` never drops below it.
 * The ref is forwarded to the underlying element so callers keep cursor access (selection, focus).
 */
const AutoTextarea = forwardRef<HTMLTextAreaElement, AutoTextareaProps>(function AutoTextarea(
  { value, style, ...rest },
  ref,
) {
  const innerRef = useRef<HTMLTextAreaElement>(null);
  useImperativeHandle(ref, () => innerRef.current as HTMLTextAreaElement, []);

  const resize = useCallback(() => {
    const el = innerRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, []);

  useLayoutEffect(() => {
    resize();
  }, [value, resize]);

  useEffect(() => {
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, [resize]);

  return (
    <textarea ref={innerRef} value={value} style={{ overflow: 'hidden', resize: 'none', ...style }} {...rest} />
  );
});

export default AutoTextarea;
