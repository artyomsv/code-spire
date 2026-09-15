import type { Readiness } from './factoryModel';

const parts: [keyof Readiness, string][] = [['source', 'source'], ['people', 'people'], ['ceiling', 'ceiling'], ['labels', 'labels']];

/**
 * The list's answer to "can this repository take work?" before anything is opened. Four bars in the
 * order of the Factory tab's steps, and one word, so the bars are never the only carrier of meaning.
 */
export default function FactoryProgress({ ready }: { ready: Readiness | null }) {
  if (!ready) return <span className="prov-none">—</span>;
  const missing = parts.filter(([part]) => !ready[part]).map(([, name]) => name);
  const text = ready.ready ? 'Ready' : ready.done === 0 ? 'Not set up' : `${ready.done} of 4 · no ${missing[0]}`;
  return <span className="factory-progress" aria-label={`Factory setup: ${text}`}>
    <span className="factory-ticks" aria-hidden="true">{parts.map(([part]) =>
      <i key={part} className={ready[part] ? 'done' : ready.done > 0 ? 'miss' : ''} />)}</span>
    <span>{ready.ready ? <b>Ready</b> : text}</span>
  </span>;
}
