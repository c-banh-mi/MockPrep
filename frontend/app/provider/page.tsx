"use client";

import { useCallback, useEffect, useState } from "react";
import { api, Appointment, fmtDateTime, fmtTime, Home, post, ProviderSlot } from "@/lib/api";
import RequireRole from "@/components/RequireRole";

function ProviderDashboard() {
  const [home, setHome] = useState<Home | null>(null);
  const [slots, setSlots] = useState<ProviderSlot[]>([]);
  const [bookings, setBookings] = useState<Appointment[]>([]);
  const [serviceId, setServiceId] = useState("");
  const [start, setStart] = useState("");
  const [message, setMessage] = useState<{ kind: string; text: string } | null>(null);

  const load = useCallback(() => {
    api<ProviderSlot[]>("/api/provider/slots").then(setSlots).catch(() => {});
    api<Appointment[]>("/api/provider/appointments?scope=upcoming").then(setBookings).catch(() => {});
  }, []);

  useEffect(() => {
    api<Home>("/api/home").then(setHome).catch(() => {});
    load();
  }, [load]);

  async function addSlot(e: React.FormEvent) {
    e.preventDefault();
    try {
      await post("/api/provider/slots", { serviceId: Number(serviceId), startTime: start });
      setMessage({ kind: "success", text: "Slot added." });
      load();
    } catch (err) {
      setMessage({ kind: "danger", text: (err as Error).message });
    }
  }

  async function remove(id: number) {
    try {
      await api(`/api/provider/slots/${id}`, { method: "DELETE" });
      setMessage({ kind: "success", text: "Slot removed." });
      load();
    } catch (err) {
      setMessage({ kind: "danger", text: (err as Error).message });
    }
  }

  return (
    <>
      <h1 className="h3 mb-3">My availability</h1>
      {message && <div className={`alert alert-${message.kind}`}>{message.text}</div>}
      <form className="row g-2 mb-4" onSubmit={addSlot}>
        <div className="col-md-5">
          <select className="form-select" value={serviceId} onChange={(e) => setServiceId(e.target.value)} required>
            <option value="">Service...</option>
            {home?.services.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.durationMinutes} min)</option>)}
          </select>
        </div>
        <div className="col-md-5">
          <input type="datetime-local" className="form-control" value={start} onChange={(e) => setStart(e.target.value)} required />
        </div>
        <div className="col-md-2"><button className="btn btn-primary w-100">Add slot</button></div>
      </form>

      <div className="row g-4">
        <div className="col-lg-6">
          <h2 className="h5">Upcoming slots</h2>
          <ul className="list-group">
            {slots.length === 0 && <li className="list-group-item text-muted">No upcoming slots.</li>}
            {slots.map((s) => (
              <li className="list-group-item d-flex justify-content-between align-items-center" key={s.id}>
                <span>{fmtDateTime(s.startTime)} – {fmtTime(s.endTime)} · {s.serviceName}{" "}
                  <span className={`badge text-bg-${s.status === "OPEN" ? "success" : "primary"}`}>{s.status}</span></span>
                {s.status === "OPEN" && <button className="btn btn-sm btn-outline-danger" onClick={() => remove(s.id)}>Remove</button>}
              </li>
            ))}
          </ul>
        </div>
        <div className="col-lg-6">
          <h2 className="h5">Booked with me</h2>
          <ul className="list-group">
            {bookings.length === 0 && <li className="list-group-item text-muted">No upcoming bookings.</li>}
            {bookings.map((a) => (
              <li className="list-group-item" key={a.id}>
                <strong>{a.customerName}</strong> · {a.serviceName}<br />
                <span className="small">{fmtDateTime(a.startTime)} – {fmtTime(a.endTime)}</span>
                {a.notes && <div className="small text-muted">“{a.notes}”</div>}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </>
  );
}

export default function ProviderPage() {
  return <RequireRole roles={["PROVIDER"]}><ProviderDashboard /></RequireRole>;
}
