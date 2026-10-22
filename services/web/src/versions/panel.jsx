import { useEffect, useState } from "react";
import { api, getAccessToken } from "../api/client.js";

export function VersionPanel({ documentId, canRestore }) {
  const [items, setItems] = useState([]);
  const [label, setLabel] = useState("");
  const [preview, setPreview] = useState("");
  const [compare, setCompare] = useState([]);
  const [selected, setSelected] = useState([]);
  const [message, setMessage] = useState("");
  const load = () => api(`/api/v1/documents/${documentId}/versions`).then((data) => setItems(data.items));
  useEffect(() => { load().catch((err) => setMessage(err.message)); }, [documentId]);
  async function name() {
    const response = await api(`/api/v1/documents/${documentId}/versions`, { method: "POST", body: JSON.stringify({ label }) });
    if (response.ready === false) {
      for (let attempt = 0; attempt < 10; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        const data = await api(`/api/v1/documents/${documentId}/versions`);
        if (data.items.some((item) => item.label === label)) break;
      }
    }
    setLabel("");
    load();
  }
  async function show(id) {
    const html = await fetch(`/api/v1/documents/${documentId}/versions/${id}/preview`, { headers: { "X-CollabDocs-Request": "1", Authorization: `Bearer ${getAccessToken()}` } }).then((response) => response.text());
    setPreview(html);
  }
  async function diff() {
    if (selected.length !== 2) return;
    const data = await api(`/api/v1/documents/${documentId}/versions/compare?from=${selected[0]}&to=${selected[1]}`);
    setCompare(data.changes || []);
  }
  async function restore(id) {
    const confirmed = window.confirm("Replace the current document with this version? People who have it open will be moved to the restored version.");
    if (!confirmed) return;
    await api(`/api/v1/documents/${documentId}/versions/${id}/restore`, { method: "POST", body: JSON.stringify({ confirm: true }) });
    setMessage("Restored");
  }
  return (
    <aside className="version-panel" aria-label="Version history">
      <h2>History</h2>
      {canRestore && (
        <div className="row">
          <label>Version name<input aria-label="Version name" value={label} onChange={(event) => setLabel(event.target.value)} /></label>
          <button type="button" onClick={name}>Name version</button>
        </div>
      )}
      {items.map((item) => (
        <div className="row" key={item.id}>
          <button type="button" className="ghost" onClick={() => show(item.id)}>{item.label || item.kind} {item.authorName || ""}</button>
          <button type="button" className="ghost" onClick={() => setSelected((current) => current.includes(item.id) ? current.filter((id) => id !== item.id) : [...current, item.id].slice(-2))}>Compare</button>
          {canRestore && <button type="button" onClick={() => restore(item.id)}>Restore</button>}
        </div>
      ))}
      <button type="button" className="ghost" onClick={diff}>Show comparison</button>
      {preview && <div data-testid="version-preview" dangerouslySetInnerHTML={{ __html: preview }} />}
      {compare.map((change, index) => <p key={index}>{change.op}: {change.text}</p>)}
      {message && <p role="status">{message}</p>}
    </aside>
  );
}

