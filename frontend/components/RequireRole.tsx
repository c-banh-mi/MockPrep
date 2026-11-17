"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Role } from "@/lib/api";
import { useAuth } from "@/lib/auth";

/** Shows children only to a logged-in user with one of the roles. The API enforces the same rule. */
export default function RequireRole({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user, ready } = useAuth();
  const path = usePathname();
  if (!ready) return <p className="text-muted">Loading...</p>;
  if (!user) {
    return (
      <div className="alert alert-info">
        Please <Link href={`/login?next=${encodeURIComponent(path)}`}>log in</Link> to continue.
      </div>
    );
  }
  if (!roles.includes(user.role)) {
    return <div className="alert alert-warning">This page is for {roles.join(" / ").toLowerCase()} accounts.</div>;
  }
  return <>{children}</>;
}
