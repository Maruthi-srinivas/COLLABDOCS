import { Mark } from "@tiptap/core";

const attrs = {
  suggestionId: { default: null },
  authorId: { default: null },
  authorName: { default: null },
};

function suggestionMark(name, attribute, className, extra = {}) {
  return Mark.create({
    name,
    inclusive: false,
    addAttributes() {
      return { ...attrs, ...extra };
    },
    parseHTML() {
      return [{ tag: `span[${attribute}]` }];
    },
    renderHTML({ HTMLAttributes }) {
      return ["span", { ...HTMLAttributes, [attribute]: HTMLAttributes.suggestionId || "", class: className }, 0];
    },
  });
}

export const SuggestionInsert = suggestionMark("suggestionInsert", "data-suggestion-insert", "suggestion-insert");
export const SuggestionDelete = suggestionMark("suggestionDelete", "data-suggestion-delete", "suggestion-delete");
export const SuggestionFormat = suggestionMark("suggestionFormat", "data-suggestion-format", "suggestion-format", {
  proposed: { default: "" },
});

export function suggestionMarks() {
  return [SuggestionInsert, SuggestionFormat, SuggestionDelete];
}
