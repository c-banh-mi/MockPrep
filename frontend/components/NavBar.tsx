"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

export default function NavBar() {
  const { user, logout } = useAuth();
  const path = usePathname();
  const router = useRouter();
  const item = (href: string, label: string) => (
    <li className="nav-item" key={href}>
      <Link className={`nav-link${path === href ? " active" : ""}`} href={href}>{label}</Link>
    </li>
  );

  return (
    <nav className="navbar navbar-expand-md navbar-dark bg-dark">
      <div className="container">
        <Link className="navbar-brand fw-semibold" href="/">MockPrep</Link>
        <ul className="navbar-nav me-auto flex-row flex-wrap gap-2 gap-md-0">
          {item("/slots", "Browse slots")}
          {item("/assistant", "AI assistant")}
          {user?.role === "CUSTOMER" && item("/appointments", "My appointments")}
          {user?.role === "PROVIDER" && item("/provider", "My availability")}
          {user?.role === "ADMIN" && item("/admin", "Admin")}
        </ul>
        <div className="d-flex align-items-center gap-2 text-light small">
          {user ? (
            <>
              <span>{user.fullName} <span className="badge text-bg-secondary">{user.role}</span></span>
              <button className="btn btn-sm btn-outline-light" onClick={() => { logout(); router.push("/"); }}>
                Log out
              </button>
            </>
          ) : (
            <Link className="btn btn-sm btn-outline-light" href={`/login?next=${encodeURIComponent(path)}`}>Log in</Link>
          )}
        </div>
      </div>
    </nav>
  );
}
