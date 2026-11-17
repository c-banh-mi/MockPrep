"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, Home, money } from "@/lib/api";

export default function HomePage() {
  const [home, setHome] = useState<Home | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Home>("/api/home").then(setHome).catch((e) => setError(e.message));
  }, []);

  return (
    <>
      <section className="hero p-4 p-md-5 mb-4">
        <h1 className="display-6 fw-semibold">Practice interviews with engineers who run them</h1>
        <p className="lead mb-4">
          Book mock coding, system design and behavioral interviews or a resume review. Or just tell the
          AI assistant what you need: it finds a slot, shows you a summary and books it once you confirm.
        </p>
        <div className="d-flex flex-wrap gap-2">
          <Link className="btn btn-primary" href="/slots">Browse open slots{home ? ` (${home.openSlotCount})` : ""}</Link>
          <Link className="btn btn-outline-light" href="/assistant">Ask the AI assistant</Link>
        </div>
      </section>

      {error && <div className="alert alert-danger">Could not reach the API: {error}</div>}

      <h2 className="h4 mb-3">Services</h2>
      <div className="row g-3 mb-4">
        {home?.services.map((s) => (
          <div className="col-sm-6 col-lg-3" key={s.id}>
            <div className="card h-100">
              <div className="card-body">
                <h3 className="h6 card-title">{s.name}</h3>
                <p className="card-text small text-muted">{s.description}</p>
                <Link className="stretched-link small" href={`/slots?serviceId=${s.id}`}>See open slots</Link>
              </div>
              <div className="card-footer small">{s.durationMinutes} min · {money(s.price)}</div>
            </div>
          </div>
        ))}
      </div>

      <h2 className="h4 mb-3">Mentors</h2>
      <div className="row g-3">
        {home?.providers.map((p) => (
          <div className="col-md-4" key={p.id}>
            <div className="card h-100">
              <div className="card-body">
                <h3 className="h6 card-title mb-1">{p.name}</h3>
                <p className="card-text small text-muted mb-0">{p.headline}</p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
