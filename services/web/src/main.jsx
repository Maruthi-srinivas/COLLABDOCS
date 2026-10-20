import { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from "@tanstack/react-query";
import { EditorContent, useEditor } from "@tiptap/react";
import * as Y from "yjs";
import { yUndoPlugin } from "y-prosemirror";
import { api, getAccessToken, refreshSession, setAccessToken } from "./api/client.js";
import { counts, editorExtensions, stripScripts } from "./editor/schema.js";
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
      <Route path="/d/:documentId" element={<Require session={session}><EditorPage /></Require>} />
      <Route path="/settings" element={<Require session={session}><Settings session={session} /></Require>} />
      <Route path="*" element={<p>Page not found. <Link to="/">Home</Link></p>} />
    </Routes>
  );
}

function Require({ session, children }) {
  if (!getAccessToken()) return <Navigate to="/login" replace />;
  if (session.user && !session.user.emailVerified) return <Navigate to="/verify-email" replace />;
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
      if (!data.user.emailVerified) navigate("/verify-email");
      else navigate(`/w/${data.workspace?.id || (await api("/api/v1/workspaces")).items[0].id}`);
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
        </form>
        <div id="content">{children}</div>
      </div>
    </div>
  );
}

function Home({ session }) {
  const workspaces = useQuery({ queryKey: ["workspaces"], queryFn: () => api("/api/v1/workspaces") });
  if (!workspaces.data) return <p role="status">Loading CollabDocs</p>;
  if (!session.user.emailVerified) return <Navigate to="/verify-email" />;
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

function EditorPage() {
  const { documentId } = useParams();
  const [state, setState] = useState(null);
  const [status, setStatus] = useState("saved");
  const [error, setError] = useState("");
  const [title, setTitle] = useState("");
  const clientId = useMemo(() => {
    const existing = sessionStorage.getItem("collabdocs-client");
    if (existing) return existing;
    const created = randomId();
    sessionStorage.setItem("collabdocs-client", created);
    return created;
  }, []);
  useEffect(() => {
    api(`/api/v1/documents/${documentId}`).then((doc) => setTitle(doc.title)).catch((err) => setError(err.message));
    api(`/api/v1/documents/${documentId}/content-state`).then(setState).catch((err) => setError(err.message));
  }, [documentId]);
  if (error) return <p role="alert">{error}</p>;
  if (!state) return <p role="status">Loading document</p>;
  return <EditorSurface documentId={documentId} state={state} title={title} setTitle={setTitle} status={status} setStatus={setStatus} clientId={clientId} />;
}

function EditorSurface({ documentId, state, title, setTitle, status, setStatus, clientId }) {
  const ydoc = useMemo(() => {
    const doc = new Y.Doc();
    Y.applyUpdate(doc, decode(state.snapshotBase64), "remote");
    for (const update of state.updates) Y.applyUpdate(doc, decode(update.payloadBase64), "remote");
    return doc;
  }, [documentId]);
  const [queue, setQueue] = useState([]);
  const editor = useEditor({
    extensions: editorExtensions(ydoc),
    editorProps: {
      attributes: { "data-testid": "editor", "aria-label": "Document" },
      transformPastedHTML: stripScripts,
    },
    onCreate: ({ editor: instance }) => instance.registerPlugin(yUndoPlugin()),
  });
  useEffect(() => {
    const onUpdate = (update, origin) => {
      if (origin === "remote") return;
      setQueue((current) => [...current, { updateId: randomId(), bytes: update }]);
      setStatus("saving");
    };
    ydoc.on("update", onUpdate);
    return () => ydoc.off("update", onUpdate);
  }, [ydoc, setStatus]);
  useEffect(() => {
    if (!queue.length) return undefined;
    const timer = setTimeout(async () => {
      const batch = queue;
      setQueue([]);
      try {
        for (const item of batch) {
          await api(`/api/v1/documents/${documentId}/content-updates`, {
            method: "POST",
            body: JSON.stringify({
              clientId, updateId: item.updateId, epoch: state.epoch, intent: "edit", payloadBase64: encode(item.bytes),
            }),
          });
        }
        setStatus("saved");
      } catch (err) {
        setQueue((current) => [...batch, ...current]);
        setStatus("error");
      }
    }, 400);
    return () => clearTimeout(timer);
  }, [queue, documentId, clientId, state.epoch, setStatus]);
  const text = editor?.getText() || "";
  const tally = counts(text);
  return (
    <main>
      <a className="skip" href="#editor-anchor">Skip to document</a>
      <div className="topbar">
        <label>Title<input aria-label="Title" value={title} onChange={(event) => setTitle(event.target.value)} onBlur={() => api(`/api/v1/documents/${documentId}`, { method: "PATCH", body: JSON.stringify({ title }) })} /></label>
        <span className="status" data-testid="save-status" role="status">{status === "saved" ? "Saved" : status === "error" ? "Could not save. Retry" : "Saving"}</span>
        {status === "error" && <button type="button" onClick={() => setStatus("saving")}>Retry</button>}
      </div>
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
      </div>
      <div id="editor-anchor"><EditorContent editor={editor} /></div>
      <p className="muted" aria-live="polite">{tally.words} words, {tally.characters} characters</p>
    </main>
  );
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
