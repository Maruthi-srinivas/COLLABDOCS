import { useEffect, useState } from "react";
import { api } from "../api/client.js";
import { resolveSuggestion } from "./resolve.js";

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function SuggestionPanel({ documentId, editor, ydoc, linkRef, canResolve, clientId, epoch }) {
  const [items, setItems] = useState([]);
  const load = () => api(`/api/v1/documents/${documentId}/suggestions?status=pending`).then((data) => setItems(data.items)).catch(() => {});
  useEffect(() => {
    load();
    if (!editor) return undefined;
    editor.on("update", load);
    return () => editor.off("update", load);
  }, [documentId, editor]);
  async function decide(item, action) {
    if (!editor) return;
    const missing = !editor.state.doc.textContent || !editor.state.doc.descendants;
    linkRef.current?.pause();
    try {
      let ranges = false;
      editor.state.doc.descendants((node) => {
        if (node.isText && node.marks.some((mark) => mark.attrs?.suggestionId === item.id)) ranges = true;
      });
      if (!ranges && !missing) {
        await api(`/api/v1/documents/${documentId}/suggestions/${item.id}`, {
          method: "PATCH",
          body: JSON.stringify({ anchorState: "detached" }),
        });
      }
      const payloadBase64 = resolveSuggestion(editor, ydoc, item, action);
      const updateId = randomId();
      await api(`/api/v1/documents/${documentId}/suggestions/${item.id}/${action}`, {
        method: "POST",
        body: JSON.stringify({ clientId, updateId, epoch: epoch || 1, intent: "edit", payloadBase64 }),
      });
      load();
    } finally {
      linkRef.current?.resume();
    }
  }
  if (!canResolve) return null;
  return (
    <div className="suggestion-list" role="region" aria-label="Suggestions">
      {items.map((item) => (
        <div className="row" key={item.id}>
          <span>{item.authorName} {item.type}{item.anchorState === "detached" ? " (detached)" : ""}</span>
          <button type="button" onClick={() => decide(item, "accept")}>Accept</button>
          <button type="button" className="ghost" onClick={() => decide(item, "reject")}>Reject</button>
        </div>
      ))}
    </div>
  );
}
