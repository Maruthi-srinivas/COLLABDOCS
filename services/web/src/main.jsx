import { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from "@tanstack/react-query";
import { EditorContent, useEditor } from "@tiptap/react";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { Decoration, DecorationSet } from "@tiptap/pm/view";
import * as Y from "yjs";
import { ySyncPluginKey, yUndoPlugin } from "y-prosemirror";
import { api, getAccessToken, refreshSession, setAccessToken } from "./api/client.js";
import { connectDocument } from "./collab/connection.js";
import { counts, editorExtensions, stripScripts } from "./editor/schema.js";
import { ExportPanel, LayoutTools, Outline, SettingsLoader, uploadPastedImage } from "./editor/extras.jsx";
import { CommentPanel } from "./comments/panel.jsx";
import { suggestionExtension } from "./suggestions/plugin.js";
import { SuggestionPanel } from "./suggestions/panel.jsx";
import { VersionPanel } from "./versions/panel.jsx";
import "./styles.css";

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const queryClient = new QueryClient();

function useSession() {
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState(null);
  useEffect(() => {
    refreshSession()
      .then(async (ok) => {
        if (ok) setUser(await api("/api/v1/me"));
      })
      .finally(() => setReady(true));
  }, []);
  return { ready, user, setUser };
}

function App() {
  const session = useSession();
  if (!session.ready) return <p role="status">Loading CollabDocs</p>;
  return (
    <Routes>
      <Route path="/login" element={<AuthPage mode="login" session={session} />} />
      <Route path="/register" element={<AuthPage mode="register" session={session} />} />
      <Route path="/forgot-password" element={<ForgotPage />} />
      <Route path="/reset-password" element={<ResetPage />} />
      <Route path="/verify-email" element={<VerifyPage session={session} />} />
      <Route path="/" element={<Require session={session}><Home session={session} /></Require>} />
      <Route path="/w/:workspaceId" element={<Require session={session}><Library session={session} /></Require>} />
      <Route path="/w/:workspaceId/folder/:folderId" element={<Require session={session}><Library session={session} /></Require>} />
      <Route path="/w/:workspaceId/starred" element={<Require session={session}><Library session={session} mode="starred" /></Require>} />
      <Route path="/w/:workspaceId/trash" element={<Require session={session}><Library session={session} mode="trash" /></Require>} />
      <Route path="/w/:workspaceId/search" element={<Require session={session}><Library session={session} mode="search" /></Require>} />
      <Route path="/d/:documentId" element={<Require session={session}><EditorPage session={session} /></Require>} />
      <Route path="/share/:token" element={<Require session={session}><RedeemPage /></Require>} />
      <Route path="/settings" element={<Require session={session}><Settings session={session} /></Require>} />
      <Route path="*" element={<p>Page not found. <Link to="/">Home</Link></p>} />
    </Routes>
  );
}

function Require({ children }) {
  if (!getAccessToken()) return <Navigate to="/login" replace />;
  return children;
}

function AuthPage({ mode, session }) {
  const navigate = useNavigate();
  const [error, setError] = useState("");
  async function onSubmit(event) {
    event.preventDefault();
    const form = new FormData(event.target);
    try {
      const path = mode === "login" ? "/api/v1/auth/login" : "/api/v1/auth/register";
      const body = mode === "login"
        ? { email: form.get("email"), password: form.get("password") }
        : { email: form.get("email"), password: form.get("password"), displayName: form.get("displayName") };
      const data = await api(path, { method: "POST", body: JSON.stringify(body), skipRefresh: true });
      setAccessToken(data.accessToken);
      session.setUser(data.user);
      navigate(`/w/${data.workspace?.id || (await api("/api/v1/workspaces")).items[0].id}`);
    } catch (err) {
      setError(err.message);
    }
  }
  return (
    <form className="stack card" style={{ padding: 24 }} onSubmit={onSubmit}>
      <h1>{mode === "login" ? "Sign in" : "Create account"}</h1>
      {mode === "register" && <label>Display name<input name="displayName" required /></label>}
      <label>Email<input name="email" type="email" required /></label>
      <label>Password<input name="password" type="password" required minLength={12} /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit">{mode === "login" ? "Sign in" : "Register"}</button>
      <Link to={mode === "login" ? "/register" : "/login"}>{mode === "login" ? "Create an account" : "Have an account?"}</Link>
      <Link to="/forgot-password">Forgot password</Link>
    </form>
  );
}

function ForgotPage() {
  const [sent, setSent] = useState(false);
  return (
    <form className="stack card" style={{ padding: 24 }} onSubmit={async (event) => {
      event.preventDefault();
      await api("/api/v1/auth/password/forgot", { method: "POST", body: JSON.stringify({ email: new FormData(event.target).get("email") }), skipRefresh: true });
      setSent(true);
    }}>
      <h1>Reset password</h1>
      {sent ? <p role="status">If an account exists, a reset message is on its way.</p> : <label>Email<input name="email" type="email" required /></label>}
      {!sent && <button type="submit">Send link</button>}
    </form>
  );
}

function ResetPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState("");
  return (
    <form className="stack card" style={{ padding: 24 }} onSubmit={async (event) => {
      event.preventDefault();
      try {
        await api("/api/v1/auth/password/reset", { method: "POST", body: JSON.stringify({ token: params.get("token"), password: new FormData(event.target).get("password") }), skipRefresh: true });
        navigate("/login");
      } catch (err) { setError(err.message); }
    }}>
      <h1>Choose a new password</h1>
      <label>New password<input name="password" type="password" required minLength={12} /></label>
      {error && <p className="error">{error}</p>}
      <button type="submit">Update password</button>
    </form>
  );
}

function VerifyPage({ session }) {
  const [params] = useSearchParams();
  const [message, setMessage] = useState("");
  const navigate = useNavigate();
  useEffect(() => {
    const token = params.get("token");
    if (!token) return;
    api("/api/v1/auth/verify-email", { method: "POST", body: JSON.stringify({ token }), skipRefresh: true })
      .then(async () => {
        await refreshSession();
        const user = await api("/api/v1/me");
        session.setUser(user);
        const workspaces = await api("/api/v1/workspaces");
        navigate(`/w/${workspaces.items[0].id}`);
      })
      .catch((err) => setMessage(err.message));
  }, [params, navigate, session]);
  return (
    <main className="stack">
      <h1>Verify your email</h1>
      <p role="status">{message || "Open the verification message and follow the link."}</p>
      <button type="button" onClick={() => api("/api/v1/auth/verify-email/resend", { method: "POST" }).then(() => setMessage("Sent."))}>Resend</button>
    </main>
  );
}

function Shell({ workspaceId, children }) {
  const [query, setQuery] = useState("");
  const navigate = useNavigate();
  return (
    <div className="shell">
      <a className="skip" href="#content">Skip to content</a>
      <aside className="sidebar">
        <strong>CollabDocs</strong>
        <Link to={`/w/${workspaceId}`}>Recent</Link>
        <Link to={`/w/${workspaceId}/starred`}>Starred</Link>
        <Link to={`/w/${workspaceId}/trash`}>Trash</Link>
        <Link to="/settings">Settings</Link>
      </aside>
      <div className="main">
        <form className="topbar" onSubmit={(event) => { event.preventDefault(); navigate(`/w/${workspaceId}/search?q=${encodeURIComponent(query)}`); }}>
          <label>Search<input value={query} onChange={(event) => setQuery(event.target.value)} /></label>
          <button type="submit">Search</button>
          <ThemeToggle />
          <NotificationBell />
        </form>
        <div id="content">{children}</div>
      </div>
    </div>
  );
}

function ThemeToggle() {
  const [theme, setTheme] = useState(() => localStorage.getItem("collabdocs-theme") || "light");
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("collabdocs-theme", theme);
  }, [theme]);
  return <button type="button" className="ghost" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>{theme === "dark" ? "Light theme" : "Dark theme"}</button>;
}

function TemplateButton({ workspaceId, folderId }) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState([]);
  useEffect(() => {
    if (!open) return;
    api(`/api/v1/templates?workspaceId=${workspaceId}`).then((data) => setItems(data.items || [])).catch(() => {});
  }, [open, workspaceId]);
  return (
    <>
      <button type="button" className="ghost" onClick={() => setOpen(true)}>From template</button>
      {open && (
        <div className="dialog-backdrop">
          <div className="dialog stack" role="dialog" aria-label="Templates">
            <h2>Templates</h2>
            {items.map((item) => (
              <button type="button" key={item.id} onClick={() => api(`/api/v1/workspaces/${workspaceId}/documents/from-template`, {
                method: "POST",
                body: JSON.stringify({ templateId: item.id, folderId: folderId || null, title: item.name }),
              }).then((created) => { window.location.assign(`/d/${created.id}`); })}>{item.name}</button>
            ))}
            <button type="button" className="ghost" onClick={() => setOpen(false)}>Close</button>
          </div>
        </div>
      )}
    </>
  );
}

function NotificationBell() {
  const [items, setItems] = useState([]);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let stopped = false;
    const load = () => api("/api/v1/notifications?unread=true").then((data) => { if (!stopped) setItems(data.items || []); }).catch(() => {});
    load();
    const timer = setInterval(load, 30000);
    return () => { stopped = true; clearInterval(timer); };
  }, []);
  return (
    <div>
      <button type="button" className="ghost" onClick={() => setOpen((value) => !value)}>Notifications{items.length ? ` (${items.length})` : ""}</button>
      {open && items.map((item) => <p key={item.id}>{item.type}</p>)}
      {open && <button type="button" className="ghost" onClick={() => api("/api/v1/notifications/read-all", { method: "POST" }).then(() => setItems([]))}>Mark read</button>}
    </div>
  );
}

function Home() {
  const workspaces = useQuery({ queryKey: ["workspaces"], queryFn: () => api("/api/v1/workspaces") });
  if (!workspaces.data) return <p role="status">Loading CollabDocs</p>;
  return <Navigate to={`/w/${workspaces.data.items[0].id}`} />;
}

function Library({ mode }) {
  const { workspaceId, folderId } = useParams();
  const [params] = useSearchParams();
  const client = useQueryClient();
  const path = mode === "starred" ? `/api/v1/workspaces/${workspaceId}/documents/starred`
    : mode === "trash" ? `/api/v1/workspaces/${workspaceId}/trash`
    : mode === "search" ? `/api/v1/workspaces/${workspaceId}/search?q=${encodeURIComponent(params.get("q") || "")}`
    : folderId ? `/api/v1/workspaces/${workspaceId}/documents?folderId=${folderId}`
    : `/api/v1/workspaces/${workspaceId}/documents/recent`;
  const docs = useQuery({ queryKey: ["docs", path], queryFn: () => api(path) });
  const folders = useQuery({ queryKey: ["folders", workspaceId, folderId || "root"], queryFn: () => api(`/api/v1/workspaces/${workspaceId}/folders${folderId ? `?parentId=${folderId}` : ""}`), enabled: !mode });
  async function createDocument() {
    const created = await api(`/api/v1/workspaces/${workspaceId}/documents`, { method: "POST", body: JSON.stringify({ folderId: folderId || null, title: "Untitled document" }) });
    window.location.assign(`/d/${created.id}`);
  }
  async function createFolder() {
    const name = window.prompt("Folder name");
    if (!name) return;
    await api(`/api/v1/workspaces/${workspaceId}/folders`, { method: "POST", body: JSON.stringify({ name, parentId: folderId || null }) });
    client.invalidateQueries({ queryKey: ["folders"] });
  }
  const items = docs.data?.items || docs.data?.documents || [];
  return (
    <Shell workspaceId={workspaceId}>
      <div className="topbar">
        <h1>{mode === "trash" ? "Trash" : mode === "starred" ? "Starred" : mode === "search" ? "Search" : folderId ? "Folder" : "Recent"}</h1>
        {!mode && <button type="button" onClick={createDocument}>New document</button>}
        {!mode && <TemplateButton workspaceId={workspaceId} folderId={folderId} />}
        {!mode && <button type="button" className="ghost" onClick={createFolder}>New folder</button>}
      </div>
      {!items.length && <p>{mode === "trash" ? "Trash is empty" : "No documents in this folder"}</p>}
      {!mode && folders.data?.items?.map((folder) => (
        <div className="row" key={folder.id}><Link to={`/w/${workspaceId}/folder/${folder.id}`}>{folder.name}</Link></div>
      ))}
      {items.map((doc) => (
        <div className="row" key={doc.id}>
          <Link to={`/d/${doc.id}`}>{doc.title}</Link>
          <span className="muted">{doc.effectiveRole}</span>
        </div>
      ))}
    </Shell>
  );
}

function EditorPage({ session }) {
  const { documentId } = useParams();
  const [meta, setMeta] = useState(null);
  const [status, setStatus] = useState("saving");
  const [error, setError] = useState("");
  const [title, setTitle] = useState("");
  const [revoked, setRevoked] = useState(false);
  const [note, setNote] = useState("");
  const [peers, setPeers] = useState({});
  const [menu, setMenu] = useState(null);
  const [sharing, setSharing] = useState(false);
  const [mode, setMode] = useState("edit");
  const [generation, setGeneration] = useState(0);
  const [restorePayload, setRestorePayload] = useState(null);
  const [commentKey, setCommentKey] = useState(0);
  const [epoch, setEpoch] = useState(1);
  const linkRef = useRef(null);
  const peersRef = useRef({});
  const ydoc = useMemo(() => new Y.Doc(), [documentId, generation]);
  const clientId = useMemo(() => {
    const existing = sessionStorage.getItem("collabdocs-client");
    if (existing) return existing;
    const created = randomId();
    sessionStorage.setItem("collabdocs-client", created);
    return created;
  }, []);
  useEffect(() => {
    api(`/api/v1/documents/${documentId}`).then((doc) => {
      setMeta(doc);
      setTitle(doc.title);
    }).catch((err) => setError(err.message));
  }, [documentId]);
  const role = meta?.effectiveRole;
  const canEdit = role === "editor" || role === "owner";
  const canSuggest = canEdit || role === "commenter";
  const intent = mode === "suggest" && canSuggest ? "suggest" : canEdit && mode !== "suggest" ? "edit" : "view";
  const readOnly = intent === "view" || Boolean(restorePayload);
  useEffect(() => {
    if (!meta) return undefined;
    const link = connectDocument({
      documentId,
      clientId,
      ydoc,
      intent,
      getToken: getAccessToken,
      onStatus: setStatus,
      onPresence: (peer) => {
        peersRef.current = { ...peersRef.current, [peer.connectionId]: peer };
        setPeers(peersRef.current);
      },
      onReady: (info) => {
        setEpoch(info.epoch);
        setMeta((current) => ({ ...current, effectiveRole: info.role }));
      },
      onRevoked: () => setRevoked(true),
      onEpoch: (payload) => setRestorePayload(payload),
      onComment: () => setCommentKey((value) => value + 1),
    });
    linkRef.current = link;
    return () => link.close();
  }, [meta?.id, intent, documentId, clientId, ydoc]);
  if (error) return <p role="alert">{error}</p>;
  if (!meta) return <p role="status">Loading document</p>;
  if (revoked) {
    return <p role="alert" data-testid="access-revoked">You no longer have access to this document. <Link to="/">Home</Link></p>;
  }
  return (
    <>
      {restorePayload && (
        <div className="dialog-backdrop">
          <div className="dialog" role="dialog" aria-label="Document restored">
            <p>This document was restored. People who have it open are moved to the restored version.</p>
            <button type="button" onClick={() => { setGeneration((value) => value + 1); setRestorePayload(null); }}>Continue</button>
          </div>
        </div>
      )}
      <EditorSurface
        documentId={documentId} ydoc={ydoc} title={title} setTitle={setTitle} status={status}
        readOnly={readOnly} peers={peers} peersRef={peersRef} linkRef={linkRef} menu={menu} setMenu={setMenu}
        sharing={sharing} setSharing={setSharing} note={note} displayName={session.user?.displayName || "You"}
        mode={mode} setMode={setMode} canSuggest={canSuggest} canEdit={canEdit} role={role}
        authorId={session.user?.id} commentKey={commentKey} epoch={epoch} clientId={clientId}
      />
    </>
  );
}

function EditorSurface({ documentId, ydoc, title, setTitle, status, readOnly, peers, peersRef, linkRef, menu, setMenu, sharing, setSharing, note, displayName, mode, setMode, canSuggest, canEdit, role, authorId, commentKey, epoch, clientId }) {
  const suggestionOptions = useRef({});
  suggestionOptions.current = { enabled: mode === "suggest" && !readOnly, documentId, authorId, authorName: displayName };
  const suggestion = useMemo(() => suggestionExtension(suggestionOptions), []);
  const readOnlyRef = useRef(readOnly);
  readOnlyRef.current = readOnly;
  const selectable = !readOnly || canSuggest;
  const [history, setHistory] = useState(false);
  const [imageMessage, setImageMessage] = useState("");
  const imageErrorRef = useRef(setImageMessage);
  imageErrorRef.current = setImageMessage;
  const editor = useEditor({
    extensions: [...editorExtensions(ydoc, documentId), suggestion],
    editable: selectable,
    editorProps: {
      attributes: { "data-testid": "editor", role: "textbox", "aria-multiline": "true", "aria-label": "Document", "aria-readonly": readOnly ? "true" : "false" },
      transformPastedHTML: stripScripts,
      handleDOMEvents: {
        dragstart(view, event) {
          const { from, to } = view.state.selection;
          if (from === to) return false;
          event.dataTransfer.setData("application/x-collabdocs", JSON.stringify({ from, to }));
          return false;
        },
        paste(view, event) {
          const image = [...(event.clipboardData?.items || [])].find((item) => item.type.startsWith("image/"));
          if (!image || readOnly) return false;
          const file = image.getAsFile();
          if (!file) return false;
          event.preventDefault();
          uploadPastedImage(documentId, file).then((created) => {
            const node = view.state.schema.nodes.image.create({ attachmentId: created.id, alt: "Pasted image", decorative: false });
            view.dispatch(view.state.tr.replaceSelectionWith(node));
          }).catch(() => imageErrorRef.current("The image could not be added."));
          return true;
        },
        drop(view, event) {
          const raw = event.dataTransfer.getData("application/x-collabdocs");
          if (!raw) return false;
          event.preventDefault();
          const source = JSON.parse(raw);
          const coords = view.posAtCoords({ left: event.clientX, top: event.clientY });
          if (!coords) return true;
          const text = view.state.doc.textBetween(source.from, source.to);
          let target = coords.pos;
          let tr = view.state.tr.delete(source.from, source.to);
          if (target > source.to) target -= source.to - source.from;
          tr = tr.insertText(text, Math.max(0, target));
          view.dispatch(tr);
          return true;
        },
        contextmenu(view, event) {
          event.preventDefault();
          setMenu({ x: event.clientX, y: event.clientY });
          return true;
        },
      },
    },
    onCreate: ({ editor: instance }) => {
      instance.registerPlugin(new Plugin({
        key: new PluginKey("review-lock"),
        filterTransaction(tr) {
          if (!readOnlyRef.current) return true;
          if (tr.getMeta(ySyncPluginKey) || tr.getMeta("suggestion-applied")) return true;
          return !tr.docChanged;
        },
      }));
      instance.registerPlugin(yUndoPlugin());
      instance.registerPlugin(new Plugin({
        key: new PluginKey("remote-carets"),
        props: {
          decorations(state) {
            const marks = Object.values(peersRef.current).map((peer) => {
              const pos = Math.max(0, Math.min(peer.anchor || 0, state.doc.content.size));
              return Decoration.widget(pos, () => {
                const caret = document.createElement("span");
                caret.dataset.testid = "remote-caret";
                caret.textContent = peer.displayName || "Peer";
                caret.style.borderLeft = `2px solid ${peer.color || "#1d4e89"}`;
                caret.style.color = peer.color || "#1d4e89";
                return caret;
              });
            });
            return DecorationSet.create(state.doc, marks);
          },
        },
      }));
    },
  }, [selectable, documentId, ydoc]);
  useEffect(() => { editor?.setEditable(selectable); }, [editor, selectable]);
  useEffect(() => { if (editor) editor.view.dispatch(editor.state.tr); }, [editor, peers]);
  useEffect(() => {
    if (!editor || readOnly) return undefined;
    editor.on("selectionUpdate", () => {
      const { from, to } = editor.state.selection;
      linkRef.current?.awareness(from, to, displayName, "#1d4e89");
    });
    return () => editor.off("selectionUpdate");
  }, [editor, readOnly, displayName]);
  const tally = projectedCounts(editor);
  return (
    <main onClick={() => setMenu(null)} className={role === "viewer" ? "viewer" : ""}>
      <a className="skip" href="#editor-anchor">Skip to document</a>
      <div className="topbar">
        <label>Title<input aria-label="Title" value={title} disabled={readOnly} onChange={(event) => setTitle(event.target.value)} onBlur={() => api(`/api/v1/documents/${documentId}`, { method: "PATCH", body: JSON.stringify({ title }) })} /></label>
        <button type="button" className="ghost" onClick={() => setSharing(true)}>Share</button>
        <button type="button" className="ghost" onClick={() => setHistory((value) => !value)}>History</button>
        <ThemeToggle />
        {canSuggest && (
          <div role="group" aria-label="Editing mode">
            <button type="button" aria-pressed={mode !== "suggest"} onClick={() => setMode("edit")}>Editing</button>
            <button type="button" aria-pressed={mode === "suggest"} onClick={() => setMode("suggest")}>Suggesting</button>
          </div>
        )}
        <button type="button" className="ghost" onClick={() => findReplace(editor)} disabled={readOnly}>Find</button>
        <span className="status" data-testid="save-status" role="status">{status === "saved" ? "Saved" : status === "error" ? "Could not save. Retry" : "Saving"}</span>
      </div>
      {note && <p role="status">{note} <button type="button" onClick={() => window.location.reload()}>Reload</button></p>}
      {readOnly && <p role="status">View only</p>}
      <div className="toolbar" role="toolbar" aria-label="Formatting">
        <Tool editor={editor} label="Bold" run={(chain) => chain.toggleBold()} active="bold" />
        <Tool editor={editor} label="Italic" run={(chain) => chain.toggleItalic()} active="italic" />
        <Tool editor={editor} label="Underline" run={(chain) => chain.toggleUnderline()} active="underline" />
        <Tool editor={editor} label="Strikethrough" run={(chain) => chain.toggleStrike()} active="strike" />
        <Tool editor={editor} label="Heading 1" run={(chain) => chain.toggleHeading({ level: 1 })} />
        <Tool editor={editor} label="Heading 2" run={(chain) => chain.toggleHeading({ level: 2 })} />
        <Tool editor={editor} label="Heading 3" run={(chain) => chain.toggleHeading({ level: 3 })} />
        <Tool editor={editor} label="Bulleted list" run={(chain) => chain.toggleBulletList()} active="bulletList" />
        <Tool editor={editor} label="Numbered list" run={(chain) => chain.toggleOrderedList()} active="orderedList" />
        <Tool editor={editor} label="Align left" run={(chain) => chain.setTextAlign("left")} />
        <Tool editor={editor} label="Align center" run={(chain) => chain.setTextAlign("center")} />
        <Tool editor={editor} label="Link" run={(chain) => {
          const href = window.prompt("Link URL");
          if (!href) return chain;
          return chain.setLink({ href });
        }} />
        <Tool editor={editor} label="Clear formatting" run={(chain) => chain.unsetAllMarks().clearNodes()} />
        <LayoutTools editor={editor} documentId={documentId} readOnly={readOnly} onError={setImageMessage} />
      </div>
      {imageMessage && <p className="error" role="alert">{imageMessage}</p>}
      <div className="editor-layout">
        <Outline editor={editor} />
        <div>
          <div id="editor-anchor"><EditorContent editor={editor} /></div>
          <p className="muted" aria-live="polite">{tally.accepted.words} words, {tally.accepted.characters} characters{mode === "suggest" ? ` (${tally.suggested.words} including suggestions)` : ""}</p>
          <SuggestionPanel documentId={documentId} editor={editor} ydoc={ydoc} linkRef={linkRef} canResolve={canEdit} clientId={clientId} epoch={epoch} />
        </div>
        <div className="side-stack">
          <SettingsLoader documentId={documentId} />
          <ExportPanel documentId={documentId} />
          <CommentPanel documentId={documentId} editor={editor} ydoc={ydoc} canComment={canSuggest} refreshKey={commentKey} />
        </div>
      </div>
      {menu && (
        <div className="dialog" style={{ position: "fixed", left: menu.x, top: menu.y }} role="menu">
          <Tool editor={editor} label="Bold" run={(chain) => chain.toggleBold()} />
          <Tool editor={editor} label="Italic" run={(chain) => chain.toggleItalic()} />
          <Tool editor={editor} label="Clear formatting" run={(chain) => chain.unsetAllMarks().clearNodes()} />
        </div>
      )}
      {history && <VersionPanel documentId={documentId} canRestore={canEdit} />}
      {sharing && <ShareDialog documentId={documentId} onClose={() => setSharing(false)} />}
    </main>
  );
}

function projectedCounts(editor) {
  let accepted = "";
  let suggested = "";
  editor?.state.doc.descendants((node) => {
    if (!node.isText) return;
    const insert = node.marks.some((mark) => mark.type.name === "suggestionInsert");
    const deletion = node.marks.some((mark) => mark.type.name === "suggestionDelete");
    if (!insert) accepted += node.text;
    if (insert || !deletion) suggested += node.text;
  });
  return { accepted: counts(accepted), suggested: counts(suggested) };
}

function findReplace(editor) {
  if (!editor) return;
  const find = window.prompt("Find");
  if (!find) return;
  const replace = window.prompt("Replace with", "") ?? "";
  const ranges = [];
  editor.state.doc.descendants((node, pos) => {
    if (!node.isText) return;
    let index = node.text.indexOf(find);
    while (index >= 0) {
      ranges.push([pos + index, pos + index + find.length]);
      index = node.text.indexOf(find, index + find.length);
    }
  });
  let tr = editor.state.tr;
  for (const [from, to] of ranges.reverse()) tr = tr.insertText(replace, from, to);
  if (ranges.length) editor.view.dispatch(tr);
}

function ShareDialog({ documentId, onClose }) {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("editor");
  const [items, setItems] = useState([]);
  const [link, setLink] = useState("");
  const [message, setMessage] = useState("");
  const load = () => api(`/api/v1/documents/${documentId}/permissions`).then((data) => setItems(data.items));
  useEffect(() => { load().catch((err) => setMessage(err.message)); }, [documentId]);
  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-label="Share">
        <h2>Share</h2>
        <label>Email<input value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Role<select value={role} onChange={(event) => setRole(event.target.value)}><option value="viewer">Viewer</option><option value="commenter">Commenter</option><option value="editor">Editor</option></select></label>
        <button type="button" onClick={() => api(`/api/v1/documents/${documentId}/permissions`, { method: "POST", body: JSON.stringify({ email, role }) }).then(() => { setEmail(""); return load(); }).catch((err) => setMessage(err.message))}>Invite</button>
        <button type="button" className="ghost" onClick={() => api(`/api/v1/documents/${documentId}/share-links`, { method: "POST", body: JSON.stringify({ role }) }).then((data) => setLink(data.url))}>Create link</button>
        {link && <p><input aria-label="Share link" readOnly value={link} /><button type="button" onClick={() => navigator.clipboard.writeText(link)}>Copy</button></p>}
        {items.map((item) => (
          <div className="row" key={item.id}>
            <span>{item.email || item.userId}</span>
            <span>{item.role}</span>
            {item.role !== "owner" && <button type="button" onClick={() => api(`/api/v1/documents/${documentId}/permissions/${item.id}`, { method: "DELETE" }).then(load)}>Revoke</button>}
          </div>
        ))}
        {message && <p className="error">{message}</p>}
        <button type="button" className="ghost" onClick={onClose}>Close</button>
      </div>
    </div>
  );
}

function RedeemPage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const [message, setMessage] = useState("Opening the shared document");
  useEffect(() => {
    api(`/api/v1/share-links/${token}/redeem`, { method: "POST" })
      .then((body) => navigate(`/d/${body.documentId}`))
      .catch((err) => setMessage(err.message));
  }, [token, navigate]);
  return <p role="status">{message}</p>;
}

function Tool({ editor, label, run, active }) {
  return <button type="button" aria-pressed={active ? !!editor?.isActive(active) : undefined} onClick={() => run(editor.chain().focus()).run()}>{label}</button>;
}

function Settings({ session }) {
  const [sessions, setSessions] = useState([]);
  const [message, setMessage] = useState("");
  useEffect(() => { api("/api/v1/auth/sessions").then((data) => setSessions(data.items)); }, []);
  return (
    <main className="stack" style={{ maxWidth: 640 }}>
      <h1>Settings</h1>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const user = await api("/api/v1/me", { method: "PATCH", body: JSON.stringify({ displayName: new FormData(event.target).get("displayName") }) });
        session.setUser(user);
        setMessage("Name saved.");
      }}>
        <label>Display name<input name="displayName" defaultValue={session.user.displayName} /></label>
        <button type="submit">Save name</button>
      </form>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const file = new FormData(event.target).get("file");
        const body = new FormData();
        body.append("file", file);
        await api("/api/v1/me/avatar", { method: "POST", body });
        setMessage("Photo saved.");
      }}>
        <label>Profile photo<input name="file" type="file" accept="image/png,image/jpeg,image/webp" /></label>
        <button type="submit">Upload</button>
      </form>
      <h2>Sessions</h2>
      {sessions.map((item) => (
        <div className="row" key={item.id}>
          <span>{item.userAgent || "Browser"} {item.current ? "(this device)" : ""}</span>
          {!item.current && <button type="button" className="ghost" onClick={() => api(`/api/v1/auth/sessions/${item.id}`, { method: "DELETE" })}>Revoke</button>}
        </div>
      ))}
      <button type="button" className="ghost" onClick={() => api("/api/v1/auth/logout-all", { method: "POST" }).then(() => { setAccessToken(null); window.location.assign("/login"); })}>Sign out everywhere</button>
      <form onSubmit={async (event) => {
        event.preventDefault();
        const form = new FormData(event.target);
        await api("/api/v1/me", { method: "DELETE", body: JSON.stringify({ password: form.get("password"), confirmation: form.get("confirmation") }) });
        setMessage("Deletion scheduled for 7 days. Sign in again to cancel.");
      }}>
        <h2>Delete account</h2>
        <label>Password<input name="password" type="password" /></label>
        <label>Type DELETE<input name="confirmation" /></label>
        <button type="submit">Delete account</button>
      </form>
      {message && <p role="status">{message}</p>}
    </main>
  );
}

function decode(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function encode(bytes) {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

createRoot(document.getElementById("root")).render(
  <QueryClientProvider client={queryClient}>
    <BrowserRouter><App /></BrowserRouter>
  </QueryClientProvider>
);
