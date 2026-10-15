import { useState } from "react";
export const uuidPattern = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
export function ContextForm({
  label,
  value,
  onOpen,
}: {
  label: string;
  value: string;
  onOpen: (value: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  return (
    <form
      className="context-form"
      onSubmit={(e) => {
        e.preventDefault();
        onOpen(draft.trim());
      }}
    >
      <label className="field">
        {label}
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          required
          pattern={uuidPattern}
          placeholder="00000000-0000-0000-0000-000000000000"
          spellCheck={false}
        />
      </label>
      <button className="button button-primary">Open workspace</button>
    </form>
  );
}
