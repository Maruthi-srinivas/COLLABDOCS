import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import * as Y from "yjs";
import { describe, expect, it } from "vitest";

describe("empty document fixture", () => {
  it("decodes the prosemirror fragment", () => {
    const bytes = readFileSync(resolve(process.cwd(), "../api/src/main/resources/collab/empty-doc-v1.bin"));
    const doc = new Y.Doc();
    Y.applyUpdate(doc, bytes);
    const fragment = doc.getXmlFragment("prosemirror");
    expect(fragment.length).toBeGreaterThan(0);
    expect(fragment.toString()).toContain("paragraph");
  });
});
