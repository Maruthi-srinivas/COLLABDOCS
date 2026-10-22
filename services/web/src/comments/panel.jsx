import { useEffect, useRef, useState } from "react";
import { api } from "../api/client.js";
import { captureAnchor, mapAnchor } from "./anchors.js";

function selectedRange(editor) {
  const { from, to } = editor.state.selection;
  if (from !== to) return { from, to };
  const selection = window.getSelection();
  if (!selection || selection.isCollapsed || !selection.anchorNode || !editor.view.dom.contains(selection.anchorNode)) return null;
  try {
    const anchor = editor.view.posAtDOM(selection.anchorNode, selection.anchorOffset);
    const head = editor.view.posAtDOM(selection.focusNode, selection.focusOffset);
    if (anchor === head) return null;
    return { from: Math.min(anchor, head), to: Math.max(anchor, head) };
  } catch {
    return null;
  }
}

export function CommentPanel({ documentId, editor, ydoc, canComment, refreshKey }) {
  const [items, setItems] = useState([]);
  const [draft, setDraft] = useState("");
  const [message, setMessage] = useState("");
  const range = useRef(null);
  const load = () => api(`/api/v1/documents/${documentId}/threads`).then((data) => setItems(data.items)).catch((err) => setMessage(err.message));
  useEffect(() => {
    load();
    const timer = setInterval(load, 2000);
    return () => clearInterval(timer);
  }, [documentId, refreshKey]);
  useEffect(() => {
    if (!editor) return undefined;
    const remember = () => {
      const next = selectedRange(editor);
      if (next) range.current = next;
    };
    editor.on("selectionUpdate", remember);
    editor.view.dom.addEventListener("mouseup", remember);
    return () => {
      editor.off("selectionUpdate", remember);
      editor.view.dom.removeEventListener("mouseup", remember);
    };
  }, [editor]);
  useEffect(() => {
    if (!ydoc) return undefined;
    const rebind = () => {
      items.forEach((thread) => {
        if (thread.anchorState === "detached") return;
        const start = mapAnchor(ydoc, thread.anchorStartBase64);
        const end = mapAnchor(ydoc, thread.anchorEndBase64);
        if (start == null || end == null || end <= start) {
          api(`/api/v1/threads/${thread.id}`, { method: "PATCH", body: JSON.stringify({ anchorState: "detached" }) }).then(load);
        }
      });
    };
    ydoc.on("update", rebind);
    return () => ydoc.off("update", rebind);
  }, [ydoc, items, documentId]);
  async function add() {
    const current = editor ? selectedRange(editor) || range.current : null;
    if (!editor || !current) {
      setMessage("Select some text first.");
      return;
    }
    const { from, to } = current;
    const quoted = editor.state.doc.textBetween(from, to, " ").slice(0, 500);
    const anchorStartBase64 = captureAnchor(ydoc, Math.max(0, from - 1));
    const anchorEndBase64 = captureAnchor(ydoc, Math.max(0, to - 1));
    if (!anchorStartBase64 || !anchorEndBase64) {
      setMessage("Comment after the document has text.");
      return;
    }
    await api(`/api/v1/documents/${documentId}/threads`, {
      method: "POST",
      body: JSON.stringify({ body: draft, quotedText: quoted, anchorStartBase64, anchorEndBase64 }),
    });
    setDraft("");
    load();
  }
  return (
    <aside className="comment-panel" aria-label="Comments">
      <h2>Comments</h2>
      {canComment && (
        <div className="stack">
          <label>Comment<textarea aria-label="Comment" value={draft} onChange={(event) => setDraft(event.target.value)} /></label>
          <button type="button" onClick={add}>Add comment</button>
        </div>
      )}
      {items.map((thread) => (
        <article key={thread.id} className="card">
          {thread.anchorState === "detached" && <p>Original text was removed</p>}
          <p className="muted">{thread.quotedText}</p>
          {(thread.comments || []).map((comment) => (
            <p key={comment.id}>{comment.deleted ? "Comment removed" : comment.body}</p>
          ))}
          {canComment && thread.status === "open" && <button type="button" className="ghost" onClick={() => api(`/api/v1/threads/${thread.id}/resolve`, { method: "POST" }).then(load)}>Resolve</button>}
        </article>
      ))}
      {message && <p className="error">{message}</p>}
    </aside>
  );
}
