// Small fetch wrapper for the Spring Boot API. Every call sends the JWT (if logged in) as
// "Authorization: Bearer <token>". Errors come back as the API's JSON {status, message}.

export type Role = "CUSTOMER" | "PROVIDER" | "ADMIN";
export interface User { id: number; username: string; fullName: string; role: Role }
export interface Service { id: number; name: string; description: string; durationMinutes: number; price: number }
export interface Provider { id: number; name: string; headline: string }
export interface Slot {
  id: number; providerId: number; providerName: string; serviceId: number; serviceName: string;
  durationMinutes: number; price: number; startTime: string; endTime: string;
}
export interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }
export interface Appointment {
  id: number; slotId: number; serviceName: string; providerName: string; customerName: string;
  startTime: string; endTime: string; status: string; notes: string | null; createdAt: string; cancelledAt: string | null;
}
export interface ProviderSlot { id: number; serviceId: number; serviceName: string; startTime: string; endTime: string; status: string }
export interface Home { appName: string; openSlotCount: number; services: Service[]; providers: Provider[] }
export interface LoginResponse { token: string; tokenType: string; expiresAt: string; user: User }
export interface ChatReply { conversationId: string; reply: string; pendingAction: string | null; actionsTaken: string[] }
export interface ChatMessage { role: "user" | "assistant"; text: string }
export interface AskReply { answer: string; sources: string[] }
export interface ReminderItem { appointmentId: number; customer: string; service: string; startTime: string; status: string; message: string }
export interface ReminderReport {
  trigger: string; startedAt: string; scanned: number; queued: number; skipped: number; failed: number; items: ReminderItem[];
}
export interface OutboxEntry {
  id: number; idempotencyKey: string; appointmentId: number; recipient: string; type: string; message: string;
  status: string; attempts: number; lastError: string | null; createdAt: string; sentAt: string | null;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

const TOKEN_KEY = "mockprep.token";

// sessionStorage: the token survives a page reload but not closing the tab.
export function getToken(): string | null {
  return typeof window === "undefined" ? null : window.sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null) {
  if (token) window.sessionStorage.setItem(TOKEN_KEY, token);
  else window.sessionStorage.removeItem(TOKEN_KEY);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && token && !path.startsWith("/api/auth/login")) {
    // Expired or invalid token: forget it, so the UI shows "logged out".
    setToken(null);
    window.dispatchEvent(new Event("mockprep:logout"));
  }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });

// Times come from the API as Pacific-time LocalDateTime strings, e.g. "2026-11-18T10:00:00".
export function fmtDateTime(iso: string) {
  return new Date(iso).toLocaleString("en-US", {
    weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit",
  });
}

export function fmtTime(iso: string) {
  return new Date(iso).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
}

export function money(n: number) {
  return `$${Number(n).toFixed(2)}`;
}
