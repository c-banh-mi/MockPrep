"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, getToken, LoginResponse, post, setToken, User } from "./api";

interface AuthState {
  user: User | null;
  ready: boolean;
  login: (username: string, password: string) => Promise<User>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  user: null, ready: false, login: async () => { throw new Error("no provider"); }, logout: () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  // On load: if a token is stored, ask the API who it belongs to (and whether it is still valid).
  useEffect(() => {
    if (!getToken()) {
      setReady(true);
      return;
    }
    api<User | undefined>("/api/auth/me")
      .then((me) => setUser(me ?? null))
      .catch(() => setToken(null))
      .finally(() => setReady(true));
  }, []);

  useEffect(() => {
    const onLogout = () => setUser(null);
    window.addEventListener("mockprep:logout", onLogout);
    return () => window.removeEventListener("mockprep:logout", onLogout);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await post<LoginResponse>("/api/auth/login", { username, password });
    setToken(res.token);
    setUser(res.user);
    return res.user;
  }, []);

  const logout = useCallback(() => {
    post("/api/auth/logout").catch(() => {});
    setToken(null);
    setUser(null);
  }, []);

  return <AuthContext.Provider value={{ user, ready, login, logout }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
