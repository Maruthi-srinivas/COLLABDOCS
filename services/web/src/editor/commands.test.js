import { Editor } from "@tiptap/core";
import { editorExtensions, stripScripts } from "./schema.js";
import { describe, expect, it } from "vitest";

function ed() {
  return new Editor({
    extensions: editorExtensions(null),
    content: "<p>Hello</p>",
    editorProps: { transformPastedHTML: stripScripts },
  });
}

describe("editor commands", () => {
  it("toggles the must-have marks and blocks", () => {
    const editor = ed();
    editor.chain().focus().selectAll().toggleBold().run();
    expect(editor.isActive("bold")).toBe(true);
    editor.chain().focus().selectAll().toggleItalic().run();
    expect(editor.isActive("italic")).toBe(true);
    editor.chain().focus().selectAll().toggleUnderline().run();
    expect(editor.isActive("underline")).toBe(true);
    editor.chain().focus().selectAll().toggleStrike().run();
    expect(editor.isActive("strike")).toBe(true);
    editor.chain().focus().selectAll().setTextAlign("center").run();
    expect(editor.isActive({ textAlign: "center" })).toBe(true);
    editor.chain().focus().selectAll().toggleHeading({ level: 2 }).run();
    expect(editor.isActive("heading", { level: 2 })).toBe(true);
    editor.chain().focus().toggleBulletList().run();
    expect(editor.isActive("bulletList")).toBe(true);
    editor.chain().focus().toggleOrderedList().run();
    expect(editor.isActive("orderedList")).toBe(true);
    editor.chain().focus().selectAll().setLink({ href: "https://example.com" }).run();
    expect(editor.isActive("link")).toBe(true);
    editor.chain().focus().selectAll().unsetAllMarks().clearNodes().run();
    expect(editor.isActive("bold")).toBe(false);
    editor.commands.setContent("<p>One</p>");
    editor.commands.insertContent(" two");
    editor.commands.undo();
    expect(editor.getText()).not.toContain("two");
    const pasted = editor.view.props.transformPastedHTML?.("<p>Hi</p><script>alert(1)</script>") ?? "";
    editor.destroy();
    expect(pasted).not.toContain("script");
  });
});
