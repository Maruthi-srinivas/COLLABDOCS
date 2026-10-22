import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import Link from "@tiptap/extension-link";
import TextAlign from "@tiptap/extension-text-align";
import Collaboration from "@tiptap/extension-collaboration";
import { suggestionMarks } from "./marks.js";

export function editorExtensions(ydoc) {
  return [
    StarterKit.configure({ history: !ydoc }),
    Underline,
    Link.configure({ openOnClick: false, autolink: false, protocols: ["http", "https", "mailto"] }),
    TextAlign.configure({ types: ["heading", "paragraph"] }),
    ...suggestionMarks(),
    ...(ydoc ? [Collaboration.configure({ document: ydoc, field: "prosemirror" })] : []),
  ];
}

export function stripScripts(html) {
  return html.replace(/<script[\s\S]*?>[\s\S]*?<\/script>/gi, "");
}

export function counts(text) {
  const characters = [...text.replace(/\n/g, "")].length;
  const words = text.trim() ? text.trim().split(/\s+/).length : 0;
  return { words, characters };
}
