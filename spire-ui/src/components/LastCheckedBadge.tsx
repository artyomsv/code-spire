import { lastCheckedLabel, type LastChecked as LastCheckedFields } from './lastChecked';

/** The credential's standing, shown beside the Check control on every provider settings page. */
export default function LastChecked({ item }: { item: LastCheckedFields }) {
  return (
    <span className={`last-checked ${item.lastCheckOk === false ? 'failed' : ''}`}>
      {lastCheckedLabel(item)}
    </span>
  );
}
