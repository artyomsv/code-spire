import { lastCheckedLabel, lastCheckedTitle, type LastChecked as LastCheckedFields } from './lastChecked';

/**
 * The credential's standing, shown beside the Check control on every provider settings page. The
 * visible text is the date; the time of day lives in the tooltip, so the badge stays on the one
 * line its table cell gives it.
 */
export default function LastChecked({ item }: { item: LastCheckedFields }) {
  return (
    <span className={`last-checked ${item.lastCheckOk === false ? 'failed' : ''}`} title={lastCheckedTitle(item)}>
      {lastCheckedLabel(item)}
    </span>
  );
}
