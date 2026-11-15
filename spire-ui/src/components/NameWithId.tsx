/**
 * A name with its id beneath, and the id once when there is no name: a row reading `3218389` over
 * `3218389` (first production install) says nothing twice.
 */
interface NameWithIdProps {
  name: string;
  id: string;
}

export function NameWithId({ name, id }: NameWithIdProps) {
  return (
    <>
      <span className="prov-name">{name}</span>
      {name !== id && <div className="prov-sub">{id}</div>}
    </>
  );
}
