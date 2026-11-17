"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api, fmtDateTime, fmtTime, Home, money, Page, Slot } from "@/lib/api";

const PAGE_SIZE = 10;

function SlotsInner() {
  const params = useSearchParams();
  const [home, setHome] = useState<Home | null>(null);
  const [serviceId, setServiceId] = useState(params.get("serviceId") ?? "");
  const [providerId, setProviderId] = useState("");
  const [date, setDate] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Slot> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Home>("/api/home").then(setHome).catch(() => {});
  }, []);

  useEffect(() => {
    const q = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (serviceId) q.set("serviceId", serviceId);
    if (providerId) q.set("providerId", providerId);
    if (date) q.set("date", date);
    api<Page<Slot>>(`/api/slots?${q}`).then((d) => { setData(d); setError(null); }).catch((e) => setError(e.message));
  }, [serviceId, providerId, date, page]);

  const reset = (fn: () => void) => { fn(); setPage(0); };

  return (
    <>
      <h1 className="h3 mb-3">Open slots</h1>
      <div className="row g-2 mb-3">
        <div className="col-md-4">
          <select className="form-select" value={serviceId} onChange={(e) => reset(() => setServiceId(e.target.value))}>
            <option value="">All services</option>
            {home?.services.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
        <div className="col-md-4">
          <select className="form-select" value={providerId} onChange={(e) => reset(() => setProviderId(e.target.value))}>
            <option value="">All mentors</option>
            {home?.providers.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="col-md-4">
          <input type="date" className="form-control" value={date} onChange={(e) => reset(() => setDate(e.target.value))} />
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}
      {data && data.items.length === 0 && <div className="alert alert-secondary">No open slots match these filters.</div>}

      {data && data.items.length > 0 && (
        <div className="table-responsive">
          <table className="table table-hover bg-white align-middle">
            <thead><tr><th>When</th><th>Service</th><th>Mentor</th><th>Price</th><th></th></tr></thead>
            <tbody>
              {data.items.map((s) => (
                <tr key={s.id}>
                  <td>{fmtDateTime(s.startTime)} – {fmtTime(s.endTime)}</td>
                  <td>{s.serviceName} <span className="text-muted small">({s.durationMinutes} min)</span></td>
                  <td>{s.providerName}</td>
                  <td>{money(s.price)}</td>
                  <td className="text-end"><Link className="btn btn-sm btn-primary" href={`/book/${s.id}`}>Book</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="d-flex align-items-center gap-2">
          <button className="btn btn-outline-secondary btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="small">Page {page + 1} of {data.totalPages} ({data.totalItems} slots)</span>
          <button className="btn btn-outline-secondary btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </>
  );
}

export default function SlotsPage() {
  return <Suspense><SlotsInner /></Suspense>;
}
