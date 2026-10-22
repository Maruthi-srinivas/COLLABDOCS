import { useEffect, useState } from "react";
import { api, getAccessToken } from "../api/client.js";
import { BACKGROUNDS, tableSize } from "./nodes.js";

export function Outline({ editor }) {
  const [items, setItems] = useState([]);
  useEffect(() => {
    if (!editor) return undefined;
    const read = () => {
      const next = [];
      editor.state.doc.descendants((node, pos) => {
        if (node.type.name === "heading") next.push({ pos, level: node.attrs.level || 1, text: node.textContent || "Heading" });
      });
      setItems(next);
    };
    read();
    editor.on("update", read);
    return () => editor.off("update", read);
  }, [editor]);
  return (
    <nav className="outline" aria-label="Outline">
      <h2>Outline</h2>
      {items.map((item) => (
        <button type="button" className="ghost" key={item.pos} style={{ paddingLeft: item.level * 8 }} onClick={() => {
          editor.chain().focus().setTextSelection(item.pos + 1).run();
          const found = editor.view.domAtPos(Math.min(item.pos + 1, editor.state.doc.content.size));
          const target = found.node.nodeType === 1 ? found.node : found.node.parentElement;
          target?.scrollIntoView?.({ block: "center" });
        }}>{item.text}</button>
      ))}
    </nav>
  );
}

export function SettingsLoader({ documentId }) {
  const [meta, setMeta] = useState(null);
  useEffect(() => {
    api(`/api/v1/documents/${documentId}`).then(setMeta).catch(() => {});
  }, [documentId]);
  if (!meta) return null;
  return <DocumentSettings documentId={documentId} meta={meta} />;
}

export function DocumentSettings({ documentId, meta }) {
  const [header, setHeader] = useState(meta?.headerText || "");
  const [footer, setFooter] = useState(meta?.footerText || "");
  const [numbers, setNumbers] = useState(Boolean(meta?.pageNumbers));
  useEffect(() => {
    setHeader(meta?.headerText || "");
    setFooter(meta?.footerText || "");
    setNumbers(Boolean(meta?.pageNumbers));
  }, [meta?.headerText, meta?.footerText, meta?.pageNumbers]);
  return (
    <form className="card stack" aria-label="Document settings" onSubmit={(event) => {
      event.preventDefault();
      api(`/api/v1/documents/${documentId}`, { method: "PATCH", body: JSON.stringify({ headerText: header, footerText: footer, pageNumbers: numbers }) }).catch(() => {});
    }}>
      <h2>Page settings</h2>
      <label>Header<input aria-label="Header" value={header} onChange={(event) => setHeader(event.target.value)} /></label>
      <label>Footer<input aria-label="Footer" value={footer} onChange={(event) => setFooter(event.target.value)} /></label>
      <label>Page numbers<input aria-label="Page numbers" type="checkbox" checked={numbers} onChange={(event) => setNumbers(event.target.checked)} /></label>
      <button type="submit">Save page settings</button>
    </form>
  );
}

export function ExportPanel({ documentId }) {
  const [status, setStatus] = useState("");
  const [jobId, setJobId] = useState("");
  async function start(format) {
    setStatus("Export queued");
    const job = await api(`/api/v1/documents/${documentId}/exports`, {
      method: "POST",
      headers: { "Idempotency-Key": `export-${Date.now()}-${Math.random().toString(16).slice(2)}` },
      body: JSON.stringify({ format, includeSuggestions: false }),
    });
    setJobId(job.id);
    for (let attempt = 0; attempt < 30; attempt += 1) {
      const current = await api(`/api/v1/exports/${job.id}`);
      setStatus(current.status === "succeeded" ? "Export ready" : current.status === "failed" ? "Export failed" : "Export queued");
      if (current.status === "succeeded" || current.status === "failed") return;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }
  async function download() {
    const response = await fetch(`/api/v1/exports/${jobId}/download`, {
      headers: { Authorization: `Bearer ${getAccessToken()}`, "X-CollabDocs-Request": "1" },
    });
    if (!response.ok) {
      setStatus("Export failed");
      return;
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "document";
    link.click();
    URL.revokeObjectURL(url);
  }
  return (
    <div className="card" role="region" aria-label="Export">
      <h2>Export</h2>
      <div className="row">
        {["txt", "html", "docx", "pdf"].map((format) => (
          <button type="button" key={format} onClick={() => start(format).catch(() => setStatus("Export failed"))}>{format.toUpperCase()}</button>
        ))}
      </div>
      <p role="status" data-testid="export-status">{status}</p>
      {status === "Export ready" && <button type="button" onClick={download}>Download</button>}
    </div>
  );
}

export function LayoutTools({ editor, documentId, readOnly, onError }) {
  const [open, setOpen] = useState(false);
  const [alt, setAlt] = useState("");
  const [decorative, setDecorative] = useState(false);
  const [file, setFile] = useState(null);
  const size = tableSize(editor);
  async function insertImage(selected) {
    const body = new FormData();
    body.set("file", selected);
    body.set("altText", decorative ? "" : alt);
    body.set("decorative", decorative ? "true" : "false");
    const created = await api(`/api/v1/documents/${documentId}/attachments`, { method: "POST", body });
    editor.chain().focus().insertContent({
      type: "image",
      attrs: { attachmentId: created.id, alt: decorative ? "" : alt, decorative },
    }).run();
    setOpen(false);
    setAlt("");
    setFile(null);
  }
  return (
    <>
      <button type="button" disabled={readOnly} onClick={() => editor?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: false }).run()}>Table</button>
      <button type="button" disabled={readOnly || !size || size.rows >= 20} onClick={() => editor.chain().focus().addRowAfter().run()}>Add row</button>
      <button type="button" disabled={readOnly || !size || size.cols >= 10} onClick={() => editor.chain().focus().addColumnAfter().run()}>Add column</button>
      <button type="button" disabled={readOnly || !size || size.rows <= 1} onClick={() => editor.chain().focus().deleteRow().run()}>Delete row</button>
      <button type="button" disabled={readOnly || !size || size.cols <= 1} onClick={() => editor.chain().focus().deleteColumn().run()}>Delete column</button>
      <button type="button" disabled={readOnly || !size} onClick={() => editor.chain().focus().mergeCells().run()}>Merge</button>
      <button type="button" disabled={readOnly || !size} onClick={() => editor.chain().focus().splitCell().run()}>Split</button>
      <label>Cell background
        <select aria-label="Cell background" disabled={readOnly || !size} defaultValue="" onChange={(event) => {
          const background = event.target.value;
          if (!BACKGROUNDS.includes(background)) return;
          const cell = editor.isActive("tableHeader") ? "tableHeader" : "tableCell";
          editor.chain().focus().setCellAttribute("background", background).updateAttributes(cell, { background }).run();
        }}>
          {BACKGROUNDS.map((color) => <option key={color || "none"} value={color}>{color || "None"}</option>)}
        </select>
      </label>
      <button type="button" disabled={readOnly || !size} onClick={() => editor.chain().focus().setCellAttribute("align", "center").updateAttributes("tableCell", { align: "center" }).run()}>Center cell</button>
      <button type="button" disabled={readOnly} onClick={() => editor?.chain().focus().insertContent({ type: "pageBreak" }).run()}>Page break</button>
      <button type="button" disabled={readOnly} onClick={() => setOpen(true)}>Image</button>
      {open && (
        <div className="dialog-backdrop">
          <form className="dialog stack" aria-label="Insert image" onSubmit={(event) => {
            event.preventDefault();
            if (!file) return;
            insertImage(file).catch((error) => onError?.(error.message || "The image could not be added."));
          }}>
            <h2>Insert image</h2>
            <label>Image file<input aria-label="Image file" type="file" accept="image/png,image/jpeg,image/gif,image/webp" onChange={(event) => setFile(event.target.files?.[0] || null)} /></label>
            <label>Alt text<input aria-label="Alt text" value={alt} onChange={(event) => setAlt(event.target.value)} /></label>
            <label>Decorative<input aria-label="Decorative" type="checkbox" checked={decorative} onChange={(event) => setDecorative(event.target.checked)} /></label>
            <button type="submit">Insert image</button>
            <button type="button" className="ghost" onClick={() => setOpen(false)}>Cancel</button>
          </form>
        </div>
      )}
    </>
  );
}

export async function uploadPastedImage(documentId, file) {
  const body = new FormData();
  body.set("file", file);
  body.set("altText", "Pasted image");
  body.set("decorative", "false");
  return api(`/api/v1/documents/${documentId}/attachments`, { method: "POST", body });
}
