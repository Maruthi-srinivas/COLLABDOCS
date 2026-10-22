import { getSchema } from "@tiptap/core";
import { prosemirrorJSONToYDoc, yXmlFragmentToProseMirrorRootNode } from "y-prosemirror";
import * as Y from "yjs";
import { schemaExtensions } from "./schema.js";

const schema = getSchema(schemaExtensions());

export function bytes(value) {
  return value instanceof Uint8Array ? value : new Uint8Array(value);
}

export function applyTail(snapshot, updates) {
  const doc = new Y.Doc();
  if (snapshot && snapshot.length) Y.applyUpdate(doc, bytes(snapshot));
  for (const update of updates) Y.applyUpdate(doc, bytes(update));
  return doc;
}

export function encodeDoc(doc) {
  return Y.encodeStateAsUpdate(doc);
}

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[character]));
}

function acceptedParagraphs(root) {
  const paragraphs = [];
  root.forEach((block) => {
    if (block.type.name === "image") {
      paragraphs.push("[image]");
      return;
    }
    if (block.type.name === "pageBreak") {
      paragraphs.push("");
      return;
    }
    let text = "";
    block.descendants((node) => {
      if (node.type.name === "image") text += "[image]";
      if (!node.isText) return;
      if (node.marks.some((mark) => mark.type.name === "suggestionInsert")) return;
      text += node.text;
    });
    paragraphs.push(text);
  });
  return paragraphs;
}

function visibleText(node, keepInserts) {
  if (node.isText) {
    if (!keepInserts && node.marks.some((mark) => mark.type.name === "suggestionInsert")) return "";
    return node.text || "";
  }
  let text = "";
  node.forEach((child) => { text += visibleText(child, keepInserts); });
  return text;
}

function renderChildren(node, keepInserts) {
  let html = "";
  node.forEach((child) => { html += renderAccepted(child, keepInserts); });
  return html;
}

function renderAccepted(node, keepInserts) {
  const name = node.type.name;
  if (name === "text") return escapeHtml(visibleText(node, keepInserts));
  if (name === "paragraph") return `<p>${renderChildren(node, keepInserts)}</p>`;
  if (name === "heading") {
    const level = node.attrs.level || 1;
    return `<h${level}>${renderChildren(node, keepInserts)}</h${level}>`;
  }
  if (name === "bulletList") return `<ul>${renderChildren(node, keepInserts)}</ul>`;
  if (name === "orderedList") return `<ol>${renderChildren(node, keepInserts)}</ol>`;
  if (name === "listItem") return `<li>${renderChildren(node, keepInserts)}</li>`;
  if (name === "blockquote") return `<blockquote>${renderChildren(node, keepInserts)}</blockquote>`;
  if (name === "table") return `<table>${renderChildren(node, keepInserts)}</table>`;
  if (name === "tableRow") return `<tr>${renderChildren(node, keepInserts)}</tr>`;
  if (name === "tableHeader") return `<th>${renderChildren(node, keepInserts)}</th>`;
  if (name === "tableCell") return `<td>${renderChildren(node, keepInserts)}</td>`;
  if (name === "image") {
    const alt = node.attrs.decorative ? "" : escapeHtml(node.attrs.alt || "");
    return `<img data-attachment-id="${escapeHtml(node.attrs.attachmentId || "")}" alt="${alt}">`;
  }
  if (name === "pageBreak") return `<div class="page-break"></div>`;
  if (name === "horizontalRule") return "<hr>";
  if (name === "doc") return renderChildren(node, keepInserts);
  return renderChildren(node, keepInserts);
}

export function acceptedHtml(root) {
  return renderAccepted(root, false);
}

export function markedHtml(root) {
  return renderAccepted(root, true);
}

export function migrate() {
  return true;
}

export function project(doc) {
  const root = yXmlFragmentToProseMirrorRootNode(doc.getXmlFragment("prosemirror"), schema);
  const canonicalJson = root.toJSON();
  const paragraphs = acceptedParagraphs(root);
  const acceptedText = paragraphs.join("\n");
  const words = acceptedText.trim() ? acceptedText.trim().split(/\s+/).length : 0;
  return {
    schemaVersion: 2,
    canonicalJson,
    acceptedText,
    acceptedHtml: acceptedHtml(root),
    markedHtml: markedHtml(root),
    wordCount: words,
    warnings: [],
  };
}

export function encodeCanonical(canonicalJson) {
  const doc = prosemirrorJSONToYDoc(schema, canonicalJson, "prosemirror");
  const snapshot = encodeDoc(doc);
  return { snapshotBase64: Buffer.from(snapshot).toString("base64"), byteLength: snapshot.length };
}

export function compactState(snapshot, updates) {
  const doc = applyTail(snapshot, updates);
  const projected = project(doc);
  return { state: encodeDoc(doc), ...projected };
}
