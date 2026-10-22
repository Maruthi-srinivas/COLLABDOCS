import { Node } from "@tiptap/core";
import { ReactNodeViewRenderer } from "@tiptap/react";
import Table from "@tiptap/extension-table";
import TableRow from "@tiptap/extension-table-row";
import TableHeader from "@tiptap/extension-table-header";
import TableCell from "@tiptap/extension-table-cell";
import { ImageView } from "./image-view.jsx";

export const BACKGROUNDS = ["", "#fff3bf", "#d3f9d8", "#d0ebff", "#ffd8a8", "#ffc9c9", "#e5dbff"];
const ALIGNS = ["left", "center", "right"];

function cellAttributes() {
  return {
    background: {
      default: "",
      parseHTML: (element) => {
        const value = element.getAttribute("data-background") || "";
        return BACKGROUNDS.includes(value) ? value : "";
      },
      renderHTML: (attributes) => (attributes.background ? { "data-background": attributes.background, style: `background-color: ${attributes.background}` } : {}),
    },
    align: {
      default: "left",
      parseHTML: (element) => {
        const value = element.getAttribute("data-align") || "left";
        return ALIGNS.includes(value) ? value : "left";
      },
      renderHTML: (attributes) => ({ "data-align": attributes.align || "left", style: `text-align: ${attributes.align || "left"}` }),
    },
  };
}

export const CollabTable = Table.configure({ resizable: false });
export const CollabTableRow = TableRow;
export const CollabTableHeader = TableHeader.extend({
  addAttributes() {
    return { ...this.parent?.(), ...cellAttributes() };
  },
});
export const CollabTableCell = TableCell.extend({
  addAttributes() {
    return { ...this.parent?.(), ...cellAttributes() };
  },
});

export const CollabImage = Node.create({
  name: "image",
  group: "block",
  atom: true,
  addOptions() {
    return { documentId: "" };
  },
  addAttributes() {
    return {
      attachmentId: { default: null },
      alt: { default: "" },
      decorative: { default: false },
    };
  },
  parseHTML() {
    return [{ tag: "img[data-attachment-id]" }];
  },
  renderHTML({ HTMLAttributes }) {
    return ["img", {
      "data-attachment-id": HTMLAttributes.attachmentId || "",
      alt: HTMLAttributes.decorative ? "" : (HTMLAttributes.alt || ""),
    }];
  },
  addNodeView() {
    return ReactNodeViewRenderer(ImageView);
  },
});

export const PageBreak = Node.create({
  name: "pageBreak",
  group: "block",
  atom: true,
  parseHTML() {
    return [{ tag: "div.page-break" }];
  },
  renderHTML() {
    return ["div", { class: "page-break" }];
  },
});

export function layoutNodes(documentId) {
  return [CollabTable, CollabTableRow, CollabTableHeader, CollabTableCell, CollabImage.configure({ documentId: documentId || "" }), PageBreak];
}

export function tableSize(editor) {
  if (!editor) return null;
  const { $from } = editor.state.selection;
  for (let depth = $from.depth; depth > 0; depth -= 1) {
    if ($from.node(depth).type.name === "table") {
      const table = $from.node(depth);
      return { rows: table.childCount, cols: table.firstChild ? table.firstChild.childCount : 0 };
    }
  }
  return null;
}
