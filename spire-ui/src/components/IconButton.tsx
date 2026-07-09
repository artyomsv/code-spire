import type { ButtonHTMLAttributes } from 'react';
import { ExternalLink, FlaskConical, Pencil, Plus, RotateCw, Trash2, type LucideIcon } from 'lucide-react';
import Tooltip from './Tooltip';

/**
 * Reusable icon action button — the same look as the review-detail actions
 * (`.icon-btn`, tinted at rest, soft-fill on hover). One `kind` picks both the
 * lucide glyph and its colour variant so every settings table shares one design.
 */
export type IconButtonKind = 'add' | 'edit' | 'test' | 'rerun' | 'open' | 'delete';

const GLYPH: Record<IconButtonKind, LucideIcon> = {
  add: Plus,
  edit: Pencil,
  test: FlaskConical,
  rerun: RotateCw,
  open: ExternalLink,
  delete: Trash2,
};

// Maps a kind to its CSS colour-variant class ('' = neutral).
const VARIANT: Record<IconButtonKind, string> = {
  add: '',
  open: '',
  edit: 'edit',
  test: 'test',
  rerun: 'rerun',
  delete: 'danger',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  kind: IconButtonKind;
  size?: number;
}

export default function IconButton({ kind, size = 16, className, type, title, ...rest }: Props) {
  const Glyph = GLYPH[kind];
  const variant = VARIANT[kind];
  // `title` drives a non-native tooltip (not the browser's default) — the raw
  // title attribute is intentionally dropped so the two don't stack.
  const button = (
    <button
      type={type ?? 'button'}
      className={['icon-btn', variant, className].filter(Boolean).join(' ')}
      {...rest}
    >
      <Glyph size={size} />
    </button>
  );
  return title ? <Tooltip label={title}>{button}</Tooltip> : button;
}
