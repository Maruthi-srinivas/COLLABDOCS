import assert from "node:assert/strict";
import test from "node:test";
import { getSchema } from "@tiptap/core";
import { prosemirrorJSONToYDoc } from "y-prosemirror";
import * as Y from "yjs";
import { compactState } from "./compact.js";
import { schemaExtensions } from "./schema.js";

test("compacts a snapshot plus one tail update", () => {
  const schema = getSchema(schemaExtensions());
  const baseDoc = prosemirrorJSONToYDoc(schema, {
    type: "doc",
    content: [{ type: "paragraph" }],
  }, "prosemirror");
  const snapshot = Y.encodeStateAsUpdate(baseDoc);
  const next = new Y.Doc();
  Y.applyUpdate(next, snapshot);
  const paragraph = next.getXmlFragment("prosemirror").get(0);
  paragraph.insert(0, [new Y.XmlText("Hi")]);
  const update = Y.encodeStateAsUpdate(next, Y.encodeStateVector(baseDoc));
  const compacted = compactState(snapshot, [update]);
  const decoded = new Y.Doc();
  Y.applyUpdate(decoded, compacted.state);
  assert.match(decoded.getXmlFragment("prosemirror").toString(), /paragraph/);
  assert.equal(compacted.acceptedText, "Hi");
  assert.equal(compacted.wordCount, 1);
});
