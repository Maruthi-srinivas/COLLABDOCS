import * as Y from "yjs";
import { encode } from "../collab/protocol.js";

export function resolveSuggestion(editor, ydoc, suggestion, action) {
  const before = Y.encodeStateVector(ydoc);
  const ranges = [];
  editor.state.doc.descendants((node, pos) => {
    if (!node.isText) return;
    const mark = node.marks.find((item) => item.attrs?.suggestionId === suggestion.id);
    if (mark) ranges.push({ pos, end: pos + node.nodeSize, mark });
  });
  if (!ranges.length) {
    ydoc.getMap("suggestions").set(suggestion.id, action);
  } else {
    let tr = editor.state.tr.setMeta("suggestion-applied", true);
    ranges.reverse().forEach(({ pos, end, mark }) => {
      const name = mark.type.name;
      if (action === "accept" && name === "suggestionInsert") tr = tr.removeMark(pos, end, mark);
      if (action === "accept" && name === "suggestionDelete") tr = tr.delete(pos, end);
      if (action === "accept" && name === "suggestionFormat") {
        tr = tr.removeMark(pos, end, mark);
        const proposed = editor.schema.marks[mark.attrs.proposed];
        if (proposed) tr = tr.addMark(pos, end, proposed.create());
      }
      if (action === "reject" && name === "suggestionInsert") tr = tr.delete(pos, end);
      if (action === "reject" && (name === "suggestionDelete" || name === "suggestionFormat")) tr = tr.removeMark(pos, end, mark);
    });
    editor.view.dispatch(tr);
  }
  return encode(Y.encodeStateAsUpdate(ydoc, before));
}
