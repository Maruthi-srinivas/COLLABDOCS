import * as Y from "yjs";
import { decode, encode } from "../collab/protocol.js";

function textNode(ydoc) {
  const paragraph = ydoc.getXmlFragment("prosemirror").get(0);
  if (!paragraph || typeof paragraph.get !== "function" || paragraph.length === 0) return null;
  const child = paragraph.get(0);
  return child instanceof Y.XmlText ? child : null;
}

export function captureAnchor(ydoc, index) {
  const text = textNode(ydoc);
  if (!text) return null;
  const relative = Y.createRelativePositionFromTypeIndex(text, Math.max(0, Math.min(index, text.length)));
  return encode(Y.encodeRelativePosition(relative));
}

export function mapAnchor(ydoc, value) {
  if (!value) return null;
  try {
    const absolute = Y.createAbsolutePositionFromRelativePosition(Y.decodeRelativePosition(decode(value.replace(/\s/g, ""))), ydoc);
    return absolute ? absolute.index : null;
  } catch {
    return null;
  }
}
