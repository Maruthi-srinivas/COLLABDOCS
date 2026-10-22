import assert from "node:assert/strict";
import test from "node:test";
import { getSchema } from "@tiptap/core";
import { prosemirrorJSONToYDoc } from "y-prosemirror";
import * as Y from "yjs";
import { compactState, project } from "./compact.js";
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

test("accepted projection hides inserts and keeps deletes and current marks", () => {
  const schema = getSchema(schemaExtensions());
  const suggestionId = "018f2c1a-7c31-7b2a-9a10-2d5e6f7a8b9c";
  const attrs = { suggestionId, authorId: "author", authorName: "Ada" };
  const doc = prosemirrorJSONToYDoc(schema, {
    type: "doc",
    content: [{
      type: "paragraph",
      content: [
        { type: "text", text: "Keep " },
        { type: "text", text: "new", marks: [{ type: "suggestionInsert", attrs }] },
        { type: "text", text: "old", marks: [{ type: "suggestionDelete", attrs }] },
        {
          type: "text",
          text: "fmt",
          marks: [
            { type: "bold" },
            { type: "suggestionFormat", attrs: { ...attrs, proposed: "italic" } },
          ],
        },
      ],
    }],
  }, "prosemirror");
  const projected = project(doc);
  assert.equal(projected.acceptedText, "Keep oldfmt");
  assert.match(projected.acceptedHtml, /<p>Keep oldfmt<\/p>/);
  assert.doesNotMatch(projected.acceptedHtml, /new|suggestion|em|strong/);
  assert.match(JSON.stringify(projected.canonicalJson), /suggestionFormat/);
  assert.match(JSON.stringify(projected.canonicalJson), /"type":"bold"/);
});

test("an accept update drops the insert mark", () => {
  const schema = getSchema(schemaExtensions());
  const marked = prosemirrorJSONToYDoc(schema, {
    type: "doc",
    content: [{
      type: "paragraph",
      content: [{
        type: "text",
        text: "Hi",
        marks: [{
          type: "suggestionInsert",
          attrs: { suggestionId: "018f2c1a-7c31-7b2a-9a10-2d5e6f7a8b9c", authorId: "author", authorName: "Ada" },
        }],
      }],
    }],
  }, "prosemirror");
  const snapshot = Y.encodeStateAsUpdate(marked);
  const live = new Y.Doc();
  Y.applyUpdate(live, snapshot);
  const vector = Y.encodeStateVector(live);
  const text = live.getXmlFragment("prosemirror").get(0).get(0);
  text.delete(0, text.length);
  text.insert(0, "Hi");
  const compacted = compactState(snapshot, [Y.encodeStateAsUpdate(live, vector)]);
  assert.equal(compacted.acceptedText, "Hi");
  assert.equal(JSON.stringify(compacted.canonicalJson).includes("suggestionInsert"), false);
});
