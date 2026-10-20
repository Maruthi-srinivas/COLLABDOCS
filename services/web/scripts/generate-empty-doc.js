import { mkdirSync, writeFileSync } from "node:fs";
import { getSchema } from "@tiptap/core";
import { prosemirrorJSONToYDoc } from "y-prosemirror";
import * as Y from "yjs";
import { editorExtensions } from "../src/editor/schema.js";

const schema = getSchema(editorExtensions(null));
const ydoc = prosemirrorJSONToYDoc(schema, {
  type: "doc",
  content: [{ type: "paragraph" }],
}, "prosemirror");
const bytes = Y.encodeStateAsUpdate(ydoc);
const target = new URL("../../api/src/main/resources/collab/empty-doc-v1.bin", import.meta.url);
mkdirSync(new URL(".", target), { recursive: true });
writeFileSync(target, Buffer.from(bytes));
console.log(`wrote ${bytes.length} bytes`);
