"use client";

// Client hook: the decoded JWT user, kept in sync with login/logout/refresh
// (AUTH_EVENT in this tab, "storage" events from other tabs). `ready` is false
// during SSR and the first render so the markup matches the server.

import { useEffect, useState } from "react";
import { AUTH_EVENT, getCurrentUser, type CurrentUser } from "@/lib/api";

export function useCurrentUser(): { user: CurrentUser | null; ready: boolean } {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const sync = () => setUser(getCurrentUser());
    sync();
    setReady(true);
    window.addEventListener(AUTH_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(AUTH_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, []);

  return { user, ready };
}
