"use client";

import Link from "next/link";
import { use, useEffect, useState } from "react";
import { api, ApiError, Appointment, fmtDateTime, fmtTime, money, post, Slot } from "@/lib/api";
import RequireRole from "@/components/RequireRole";

export default function BookPage({ params }: { params: Promise<{ slotId: string }> }) {
  const { slotId } = use(params);
  const [slot, setSlot] = useState<Slot | null>(null);
  const [notes, setNotes] = useState("");
  const [booked, setBooked] = useState<Appointment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Slot>(`/api/slots/${slotId}`).then(setSlot).catch((e) => setError(e.message));
  }, [slotId]);

  async function book() {
    if (!slot) return;
    setBusy(true);
    setError(null);
    try {
      setBooked(await post<Appointment>("/api/customer/appointments",
        { slotId: slot.id, serviceId: slot.serviceId, notes: notes || null }));
    } catch (e) {
      // 409: someone else booked it first (double-booking prevented on the server).
      setError(e instanceof ApiError && e.status === 409 ? `Sorry, ${e.message.toLowerCase()}.` : (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (booked) {
    return (
      <div className="card mx-auto" style={{ maxWidth: 560 }}>
        <div className="card-body">
          <h1 className="h4 text-success">Booked!</h1>
          <p className="mb-1"><strong>{booked.serviceName}</strong> with {booked.providerName}</p>
          <p className="mb-1">{fmtDateTime(booked.startTime)} – {fmtTime(booked.endTime)}</p>
          <p className="text-muted small">Appointment #{booked.id}. A confirmation message is on its way.</p>
          <Link className="btn btn-primary" href="/appointments">My appointments</Link>
        </div>
      </div>
    );
  }

  return (
    <RequireRole roles={["CUSTOMER"]}>
      <div className="card mx-auto" style={{ maxWidth: 560 }}>
        <div className="card-body">
          <h1 className="h4">Book this session</h1>
          {error && <div className="alert alert-danger">{error} <Link href="/slots">Browse other slots</Link></div>}
          {slot && (
            <>
              <dl className="row mb-3">
                <dt className="col-4">Service</dt><dd className="col-8">{slot.serviceName} ({slot.durationMinutes} min)</dd>
                <dt className="col-4">Mentor</dt><dd className="col-8">{slot.providerName}</dd>
                <dt className="col-4">When</dt><dd className="col-8">{fmtDateTime(slot.startTime)} – {fmtTime(slot.endTime)}</dd>
                <dt className="col-4">Price</dt><dd className="col-8">{money(slot.price)}</dd>
              </dl>
              <label className="form-label" htmlFor="notes">Notes for the mentor (optional)</label>
              <textarea id="notes" className="form-control mb-3" rows={3} maxLength={500} value={notes}
                placeholder="Target role, resume link, topics to focus on" onChange={(e) => setNotes(e.target.value)} />
              <button className="btn btn-primary" disabled={busy} onClick={book}>{busy ? "Booking..." : "Confirm booking"}</button>
            </>
          )}
        </div>
      </div>
    </RequireRole>
  );
}
