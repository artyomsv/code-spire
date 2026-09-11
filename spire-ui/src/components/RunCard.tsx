import type { ReactNode } from 'react';

export default function RunCard({ title, children }: { title: string; children: ReactNode }) {
  return <section className="card" aria-label={title}>
    <div className="head"><h3>{title}</h3></div>
    <div className="body">{children}</div>
  </section>;
}

export function RunFields({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="run-fields">{rows.map(([label, value]) => (
    <div key={label}><dt>{label}</dt><dd>{value ?? '—'}</dd></div>
  ))}</dl>;
}
