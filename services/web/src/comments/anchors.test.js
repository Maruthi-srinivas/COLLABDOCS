import assert from "node:assert/strict";
import { test } from "vitest";
import * as Y from "yjs";
import { captureAnchor, mapAnchor } from "./anchors.js";

function documentWith(text) {
  const doc = new Y.Doc();
  const paragraph = new Y.XmlElement("paragraph");
  const body = new Y.XmlText();
  body.insert(0, text);
  paragraph.insert(0, [body]);
  doc.getXmlFragment("prosemirror").insert(0, [paragraph]);
  return doc;
}

test("a comment anchor survives an insert before the range", () => {
  const doc = documentWith("Hello");
  const start = captureAnchor(doc, 1);
  const end = captureAnchor(doc, 5);
  doc.getXmlFragment("prosemirror").get(0).get(0).insert(0, "X");
  assert.equal(mapAnchor(doc, start) > 1, true);
  assert.equal(mapAnchor(doc, end), 6);
});

test("deleting the anchored text detaches the anchor", () => {
  const doc = documentWith("Hello");
  const start = captureAnchor(doc, 0);
  const end = captureAnchor(doc, 5);
  doc.getXmlFragment("prosemirror").get(0).delete(0, 1);
  assert.equal(mapAnchor(doc, start), null);
  assert.equal(mapAnchor(doc, end), null);
});
