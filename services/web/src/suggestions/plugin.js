import { Extension } from "@tiptap/core";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { AddMarkStep, ReplaceStep } from "@tiptap/pm/transform";
import { ySyncPluginKey } from "y-prosemirror";
import { api } from "../api/client.js";

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function suggestionExtension(optionsRef) {
  return Extension.create({
    name: "suggestionMode",
    addProseMirrorPlugins() {
      return [new Plugin({
        key: new PluginKey("suggestionMode"),
        appendTransaction(transactions, oldState, newState) {
          const options = optionsRef.current;
          if (!options?.enabled) return null;
          if (transactions.some((tr) => tr.getMeta("suggestion-applied") || tr.getMeta(ySyncPluginKey))) return null;
          if (!transactions.some((tr) => tr.docChanged)) return null;
          let tr = newState.tr.setMeta("suggestion-applied", true);
          let changed = false;
          const allocate = (type, payload) => {
            const id = randomId();
            api(`/api/v1/documents/${options.documentId}/suggestions`, {
              method: "POST",
              body: JSON.stringify({ id, type, payload: payload || {} }),
            }).catch(() => {});
            return id;
          };
          transactions.forEach((transaction) => {
            transaction.steps.forEach((step, index) => {
              const mapAfter = transaction.mapping.slice(index + 1);
              if (step instanceof AddMarkStep && !step.mark.type.name.startsWith("suggestion")) {
                const from = mapAfter.map(step.from);
                const to = mapAfter.map(step.to, -1);
                const id = allocate("format", { proposed: step.mark.type.name });
                tr = tr.removeMark(from, to, step.mark.type);
                tr = tr.addMark(from, to, newState.schema.marks.suggestionFormat.create({
                  suggestionId: id, authorId: options.authorId, authorName: options.authorName, proposed: step.mark.type.name,
                }));
                changed = true;
                return;
              }
              if (!(step instanceof ReplaceStep)) return;
              const mappedFrom = mapAfter.map(step.from);
              if (step.to > step.from) {
                const slice = oldState.doc.slice(step.from, step.to);
                const id = allocate("delete");
                tr = tr.insert(mappedFrom, slice.content);
                tr = tr.addMark(mappedFrom, mappedFrom + slice.size, newState.schema.marks.suggestionDelete.create({
                  suggestionId: id, authorId: options.authorId, authorName: options.authorName,
                }));
                changed = true;
              }
              if (step.slice && step.slice.size) {
                const deleted = step.to > step.from ? oldState.doc.slice(step.from, step.to).size : 0;
                const start = mappedFrom + deleted;
                const id = allocate("insert");
                tr = tr.addMark(start, start + step.slice.size, newState.schema.marks.suggestionInsert.create({
                  suggestionId: id, authorId: options.authorId, authorName: options.authorName,
                }));
                changed = true;
              }
            });
          });
          return changed ? tr : null;
        },
      })];
    },
  });
}
