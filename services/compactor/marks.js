import { Mark } from "@tiptap/core";

const attrs = {
  suggestionId: { default: null },
  authorId: { default: null },
  authorName: { default: null },
};

function suggestionMark(name, extra = {}) {
  return Mark.create({
    name,
    addAttributes() {
      return { ...attrs, ...extra };
    },
  });
}

export function suggestionMarks() {
  return [
    suggestionMark("suggestionInsert"),
    suggestionMark("suggestionDelete"),
    suggestionMark("suggestionFormat", { proposed: { default: "" } }),
  ];
}
