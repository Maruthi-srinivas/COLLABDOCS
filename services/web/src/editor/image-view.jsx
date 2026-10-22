import { NodeViewWrapper } from "@tiptap/react";
import { useEffect, useState } from "react";
import { getAccessToken } from "../api/client.js";

export function ImageView({ node, extension }) {
  const [src, setSrc] = useState("");
  const attachmentId = node.attrs.attachmentId;
  const documentId = extension.options.documentId;
  useEffect(() => {
    if (!attachmentId || !documentId) return undefined;
    let url = "";
    let cancelled = false;
    fetch(`/api/v1/documents/${documentId}/attachments/${attachmentId}`, {
      headers: { Authorization: `Bearer ${getAccessToken()}`, "X-CollabDocs-Request": "1" },
    }).then((response) => (response.ok ? response.blob() : null)).then((blob) => {
      if (cancelled || !blob) return;
      url = URL.createObjectURL(blob);
      setSrc(url);
    }).catch(() => {});
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [attachmentId, documentId]);
  return (
    <NodeViewWrapper className="image-block">
      {src ? <img src={src} alt={node.attrs.decorative ? "" : (node.attrs.alt || "")} data-attachment-id={attachmentId || ""} /> : <span className="muted">Image</span>}
    </NodeViewWrapper>
  );
}
