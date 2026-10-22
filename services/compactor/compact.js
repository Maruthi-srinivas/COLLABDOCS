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
    let text = "";
    block.descendants((node) => {
      if (!node.isText) return;
      if (node.marks.some((mark) => mark.type.name === "suggestionInsert")) return;
      text += node.text;
    });
    paragraphs.push(text);
  });
  return paragraphs;
}

export function acceptedHtml(root) {
  return acceptedParagraphs(root).map((line) => `<p>${escapeHtml(line)}</p>`).join("");
}

export function project(doc) {
  const root = yXmlFragmentToProseMirrorRootNode(doc.getXmlFragment("prosemirror"), schema);
  const canonicalJson = root.toJSON();
  const paragraphs = acceptedParagraphs(root);
  const acceptedText = paragraphs.join("\n");
  const words = acceptedText.trim() ? acceptedText.trim().split(/\s+/).length : 0;
  return {
    schemaVersion: 1,
    canonicalJson,
    acceptedText,
    acceptedHtml: acceptedHtml(root),
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
