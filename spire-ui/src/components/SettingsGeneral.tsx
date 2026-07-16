import ConversationSettings from './ConversationSettings';

/** General preferences — small global options (conversation tuning; future toggles land here). */
export default function SettingsGeneral() {
  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">General</h2>
        </div>
        <div style={{ padding: '4px 18px 18px' }}>
          <ConversationSettings />
        </div>
      </div>
    </section>
  );
}
