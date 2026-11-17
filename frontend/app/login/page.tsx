"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";

const HOME_BY_ROLE = { CUSTOMER: "/appointments", PROVIDER: "/provider", ADMIN: "/admin" } as const;

function LoginInner() {
  const { login } = useAuth();
  const router = useRouter();
  const next = useSearchParams().get("next");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = await login(username, password);
      const target = next && next.startsWith("/") && next !== "/login" ? next : HOME_BY_ROLE[user.role];
      router.push(target);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card mx-auto" style={{ maxWidth: 420 }}>
      <form className="card-body" onSubmit={submit}>
        <h1 className="h4 mb-3">Log in</h1>
        {error && <div className="alert alert-danger py-2">{error}</div>}
        <input className="form-control mb-2" placeholder="Username" autoComplete="username" value={username}
          onChange={(e) => setUsername(e.target.value)} required />
        <input className="form-control mb-3" type="password" placeholder="Password" autoComplete="current-password"
          value={password} onChange={(e) => setPassword(e.target.value)} required />
        <button className="btn btn-primary w-100" disabled={busy}>{busy ? "Logging in..." : "Log in"}</button>
        <p className="small text-muted mt-3 mb-0">
          Demo accounts (password <code>password123</code>): customers <code>sam.dev</code>, <code>jordan.dev</code>;
          mentors <code>alice.mentor</code>, <code>raj.mentor</code>, <code>maria.mentor</code>; admin <code>admin</code>.
        </p>
      </form>
    </div>
  );
}

export default function LoginPage() {
  return <Suspense><LoginInner /></Suspense>;
}
