"use client";

import { useEffect, useRef, useState } from "react";
import { api, AskReply, ChatMessage, ChatReply, post } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import RequireRole from "@/components/RequireRole";

const CONVO_KEY = "mockprep.conversation";

function newConversationId() {
  // crypto.randomUUID only exists in secure contexts (https or localhost).
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/** Agent 1: the conversational booking agent (customers). */
function BookingChat() {
  const [conversationId, setConversationId] = useState<string>("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pending, setPending] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);

  // Continue the last conversation: its messages are stored server-side (PostgreSQL chat memory).
  useEffect(() => {
    const id = window.localStorage.getItem(CONVO_KEY) ?? newConversationId();
    window.localStorage.setItem(CONVO_KEY, id);
    setConversationId(id);
    api<ChatMessage[]>(`/api/assistant/chat/${id}`).then(setMessages).catch(() => {});
  }, []);

  useEffect(() => {
    box.current?.scrollTo({ top: box.current.scrollHeight });
  }, [messages, busy]);

  async function send(text: string) {
    if (!text.trim() || busy) return;
    setMessages((m) => [...m, { role: "user", text }]);
    setInput("");
    setBusy(true);
    setError(null);
    try {
      const reply = await post<ChatReply>("/api/assistant/chat", { conversationId, message: text });
      const done = reply.actionsTaken.map((a) => `✔ ${a}`).join("\n");
      setMessages((m) => [...m, { role: "assistant", text: done ? `${reply.reply}\n\n${done}` : reply.reply }]);
      setPending(reply.pendingAction);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function startOver() {
    await api(`/api/assistant/chat/${conversationId}`, { method: "DELETE" }).catch(() => {});
    const id = newConversationId();
    window.localStorage.setItem(CONVO_KEY, id);
    setConversationId(id);
    setMessages([]);
    setPending(null);
  }

  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <span className="fw-semibold">Booking assistant</span>
        <button className="btn btn-sm btn-outline-secondary" onClick={startOver}>New conversation</button>
      </div>
      <div className="card-body">
        <div className="chat-box p-3 mb-3" ref={box}>
          {messages.length === 0 && (
            <p className="text-muted small mb-0">
              Try: “Find the earliest mock coding interview this week and book it”, “What do I have coming up?”,
              or “Cancel my resume review”. Nothing is booked or cancelled until you confirm.
            </p>
          )}
          {messages.map((m, i) => <div key={i} className={`msg msg-${m.role}`}>{m.text}</div>)}
          {busy && <div className="msg msg-assistant text-muted">Thinking...</div>}
        </div>
        {error && <div className="alert alert-danger py-2">{error}</div>}
        {pending && (
          <div className="alert alert-warning d-flex flex-wrap justify-content-between align-items-center gap-2">
            <span><strong>Waiting for your confirmation:</strong> {pending}</span>
            <span className="d-flex gap-2">
              <button className="btn btn-sm btn-success" disabled={busy} onClick={() => send("Yes, confirm.")}>Confirm</button>
              <button className="btn btn-sm btn-outline-secondary" disabled={busy} onClick={() => send("No, don't do that.")}>No</button>
            </span>
          </div>
        )}
        <form className="d-flex gap-2" onSubmit={(e) => { e.preventDefault(); send(input); }}>
          <input className="form-control" placeholder="Ask the assistant..." value={input} maxLength={1000}
            onChange={(e) => setInput(e.target.value)} />
          <button className="btn btn-primary" disabled={busy || !input.trim()}>Send</button>
        </form>
      </div>
    </div>
  );
}

/** Grounded Q&A (RAG) over the MockPrep handbook. */
function PolicyQa() {
  const [question, setQuestion] = useState("");
  const [reply, setReply] = useState<AskReply | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setReply(await post<AskReply>("/api/assistant/ask", { question }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="card-header fw-semibold">Policies &amp; FAQ</div>
      <div className="card-body">
        <form className="mb-3" onSubmit={ask}>
          <input className="form-control mb-2" placeholder="e.g. What happens if I cancel late?" value={question}
            maxLength={500} onChange={(e) => setQuestion(e.target.value)} required />
          <button className="btn btn-outline-primary w-100" disabled={busy}>{busy ? "Looking it up..." : "Ask"}</button>
        </form>
        {error && <div className="alert alert-danger py-2">{error}</div>}
        {reply && (
          <>
            <p style={{ whiteSpace: "pre-wrap" }}>{reply.answer}</p>
            <p className="small text-muted mb-0">
              Sources: {reply.sources.length ? reply.sources.join(", ") : "none found in the handbook"}
            </p>
          </>
        )}
        <p className="small text-muted mt-3 mb-0">Answers come only from MockPrep&apos;s handbook (retrieval-augmented generation).</p>
      </div>
    </div>
  );
}

function Assistant() {
  const { user } = useAuth();
  return (
    <>
      <h1 className="h3 mb-3">AI assistant</h1>
      <div className="row g-4">
        <div className="col-lg-8">
          {user?.role === "CUSTOMER" ? <BookingChat /> : (
            <div className="alert alert-secondary">The booking assistant books and cancels as a customer. Log in as a customer to use it.</div>
          )}
        </div>
        <div className="col-lg-4"><PolicyQa /></div>
      </div>
    </>
  );
}

export default function AssistantPage() {
  return <RequireRole roles={["CUSTOMER", "PROVIDER", "ADMIN"]}><Assistant /></RequireRole>;
}
