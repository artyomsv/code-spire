/** Format an ISO-8601 instant as a compact local timestamp, e.g. "Jul 18, 00:22:40". Falls
 * back to the raw value if it doesn't parse (older rows may carry a pre-formatted delta). */
export function formatEventTime(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}
