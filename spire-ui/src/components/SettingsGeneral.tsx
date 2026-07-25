import ConversationSettings from './ConversationSettings';
import ReviewSettings from './ReviewSettings';

/**
 * General preferences — the small global knobs. The two groups are titled as plain headings rather
 * than separate bordered cards: the retry budgets they hold are different (a review reports a failure,
 * a follow-up dead-letters), so they must read as distinct sections, but nesting panels inside a panel
 * makes each heading look like another widget.
 */
export default function SettingsGeneral() {
  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">General</h2>
        </div>
        <div style={{ padding: '4px 18px 18px' }}>
          <h3 className="settings-group">Code review</h3>
          <ReviewSettings />

          <h3 className="settings-group">Conversation</h3>
          <ConversationSettings />
        </div>
      </div>
    </section>
  );
}
