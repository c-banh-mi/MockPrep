"use client";

import { useCallback, useEffect, useState } from "react";
import { api, fmtDateTime, OutboxEntry, post, ReminderReport } from "@/lib/api";
import RequireRole from "@/components/RequireRole";

const STATUS_BADGE: Record<string, string> = { SENT: "success", PENDING: "warning", FAILED: "danger" };

function Admin() {
  const [report, setReport] = useState<ReminderReport | null>(null);
  const [outbox, setOutbox] = useState<OutboxEntry[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadOutbox = useCallback(() => {
    api<OutboxEntry[]>("/api/admin/notifications?limit=30").then(setOutbox).catch((e) => setError(e.message));
  }, []);

  useEffect(loadOutbox, [loadOutbox]);

  async function runAgent() {
    setBusy(true);
    setError(null);
    try {
      setReport(await post<ReminderReport>("/api/admin/agents/reminders/run"));
      setTimeout(loadOutbox, 1000);   // the relay delivers right after the commit
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1 className="h3 mb-3">Admin</h1>
      {error && <div className="alert alert-danger">{error}</div>}

      <div className="card mb-4">
        <div className="card-body">
          <h2 className="h5">Autonomous reminder agent</h2>
          <p className="small text-muted">
            Runs by itself every hour. It finds booked sessions starting in the next 24 hours, has Gemini write a
            personalized reminder with preparation tips from the handbook, and sends it through the notification
            service. This button runs the same job now.
          </p>
          <button className="btn btn-primary" onClick={runAgent} disabled={busy}>{busy ? "Running..." : "Run now"}</button>
          {report && (
            <div className="mt-3">
              <p className="mb-2">
                Scanned {report.scanned} · queued {report.queued} · skipped {report.skipped} · failed {report.failed}
              </p>
              {report.items.map((it) => (
                <div className="border rounded p-2 mb-2 bg-light" key={it.appointmentId}>
                  <div className="small fw-semibold">
                    #{it.appointmentId} {it.customer} · {it.service} · {it.startTime}{" "}
                    <span className={`badge text-bg-${it.status === "QUEUED" ? "success" : "secondary"}`}>{it.status}</span>
                  </div>
                  <div className="outbox-message">{it.message}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="d-flex justify-content-between align-items-center mb-2">
        <h2 className="h5 mb-0">Notification outbox</h2>
        <button className="btn btn-sm btn-outline-secondary" onClick={loadOutbox}>Refresh</button>
      </div>
      <div className="table-responsive">
        <table className="table table-sm bg-white align-middle">
          <thead><tr><th>#</th><th>Type</th><th>To</th><th>Message</th><th>Status</th><th>Tries</th><th>Created</th></tr></thead>
          <tbody>
            {outbox.map((n) => (
              <tr key={n.id}>
                <td>{n.id}</td>
                <td className="small">{n.type}</td>
                <td className="small">{n.recipient}</td>
                <td className="outbox-message">{n.message}</td>
                <td><span className={`badge text-bg-${STATUS_BADGE[n.status] ?? "secondary"}`}>{n.status}</span></td>
                <td>{n.attempts}</td>
                <td className="small">{fmtDateTime(n.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

export default function AdminPage() {
  return <RequireRole roles={["ADMIN"]}><Admin /></RequireRole>;
}
