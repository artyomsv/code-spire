import type { DraftComment, WrittenArtifact } from './workDraftApi';

interface Props {
  specification: WrittenArtifact;
  /** The ticket, linked while the comment has no address of its own. */
  ticketLink: string;
}

/** Only an address the tracker returned is opened; a bare comment id is not a link. */
const isAddress = (ref: string | null) => !!ref && /^https?:\/\//.test(ref);

/** Where the specification stands on the ticket, in words, with the link that shows it. */
function CommentLine({ comment, ticketLink }: { comment: DraftComment | null; ticketLink: string }) {
  if (!comment) return <p className="prov-note">This tracker takes no comments, so the specification is only shown here.</p>;
  if (comment.state === 'refused') return <p className="prov-note">The specification was not posted to the ticket.</p>;
  const sent = comment.state === 'sent';
  const href = sent && comment.ref && isAddress(comment.ref) ? comment.ref : ticketLink;
  return <p className="factory-note"><a href={href} target="_blank" rel="noreferrer">{sent ? 'Posted to the ticket' : 'Being posted to the ticket'}</a></p>;
}

/**
 * The specification the factory wrote (M4 slice 3, §6.10). It is model output from a ticket anyone could write, so it
 * is shown as text exactly as stored: never parsed as Markdown, never set as HTML.
 */
export default function SpecificationView({ specification, ticketLink }: Props) {
  return <div>
    <p className="prov-sub">Specification — written by the factory · digest <code>{specification.sha256.slice(0, 12)}</code></p>
    <pre className="decision-quote" aria-label="Specification text">{specification.text}</pre>
    <CommentLine comment={specification.comment} ticketLink={ticketLink} />
  </div>;
}
