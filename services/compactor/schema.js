import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import Link from "@tiptap/extension-link";
import TextAlign from "@tiptap/extension-text-align";
import { suggestionMarks } from "./marks.js";

export function schemaExtensions() {
  return [
    StarterKit.configure({ history: false }),
    Underline,
    Link.configure({ openOnClick: false, autolink: false, protocols: ["http", "https", "mailto"] }),
    TextAlign.configure({ types: ["heading", "paragraph"] }),
    ...suggestionMarks(),
  ];
}
