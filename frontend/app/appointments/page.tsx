"use client";

import { useCallback, useEffect, useState } from "react";
import { api, Appointment, fmtDateTime, fmtTime, post } from "@/lib/api";
import RequireRole from "@/components/RequireRole";

const BADGE: Record<string, string> = { BOOKED: "primary", CANCELLED: "secondary", COMPLETED: "success" };

function Appointments() {
  const [scope, setScope] = useState<"upcoming" | "history">("upcoming");
  const [list, setList] = useState<Appointment[] | null>(null);
  const [message, setMessage] = useState<{ kind: string; text: string } | null>(null);

  const load = useCallback(() => {
    api<Appointment[]>(`/api/customer/appointments?scope=${scope}`).then(setList)
      .catch((e) => setMessage({ kind: "danger", text: e.message }));
  }, [scope]);

  useEffect(load, [load]);

  async function cancel(a: Appointment) {
    if (!window.confirm(`Cancel ${a.serviceName} with ${a.providerName} on ${fmtDateTime(a.startTime)}?`)) return;
    try {
      await post(`/api/customer/appointments/${a.id}/cancel`);
      setMessage({ kind: "success", text: `Appointment #${a.id} cancelled. The slot is open again.` });
      load();
    } catch (e) {
      setMessage({ kind: "danger", text: (e as Error).message });
    }
  }

  return (
    <>
      <h1 className="h3 mb-3">My appointments</h1>
      <ul className="nav nav-tabs mb-3">
        {(["upcoming", "history"] as const).map((s) => (
          <li className="nav-item" key={s}>
            <button className={`nav-link${scope === s ? " active" : ""}`} onClick={() => setScope(s)}>
              {s === "upcoming" ? "Upcoming" : "History"}
            </button>
          </li>
        ))}
      </ul>
      {message && <div className={`alert alert-${message.kind}`}>{message.text}</div>}
      {list && list.length === 0 && <div className="alert alert-secondary">Nothing here yet.</div>}
      {list && list.length > 0 && (
        <div className="table-responsive">
          <table className="table bg-white align-middle">
            <thead><tr><th>When</th><th>Service</th><th>Mentor</th><th>Status</th><th>Notes</th><th></th></tr></thead>
            <tbody>
              {list.map((a) => (
                <tr key={a.id}>
                  <td>{fmtDateTime(a.startTime)} – {fmtTime(a.endTime)}</td>
                  <td>{a.serviceName}</td>
                  <td>{a.providerName}</td>
                  <td><span className={`badge text-bg-${BADGE[a.status] ?? "secondary"}`}>{a.status}</span></td>
                  <td className="small">{a.notes}</td>
                  <td className="text-end">
                    {a.status === "BOOKED" && scope === "upcoming" && (
                      <button className="btn btn-sm btn-outline-danger" onClick={() => cancel(a)}>Cancel</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export default function AppointmentsPage() {
  return <RequireRole roles={["CUSTOMER"]}><Appointments /></RequireRole>;
}
