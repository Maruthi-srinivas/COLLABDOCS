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

export function project(doc) {
  const root = yXmlFragmentToProseMirrorRootNode(doc.getXmlFragment("prosemirror"), schema);
  const canonicalJson = root.toJSON();
  const acceptedText = root.textContent || "";
  const words = acceptedText.trim() ? acceptedText.trim().split(/\s+/).length : 0;
  return { schemaVersion: 1, canonicalJson, acceptedText, wordCount: words, warnings: [] };
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
