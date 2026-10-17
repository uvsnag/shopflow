"use client";

// FR-11: in-app notifications, newest first.

import { useEffect, useState } from "react";
import { ApiError, errorMessage, getNotifications, type Notification } from "@/lib/api";
import { ErrorText, formatDate, LoginPrompt, styles } from "@/components/ui";

export default function NotificationsPage() {
  const [items, setItems] = useState<Notification[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [needsLogin, setNeedsLogin] = useState(false);

  useEffect(() => {
    let active = true;
    getNotifications(0, 50)
      .then((list) => {
        if (!active) return;
        const sorted = [...list].sort((a, b) => {
          const byDate = new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
          return Number.isNaN(byDate) ? b.id - a.id : byDate || b.id - a.id;
        });
        setItems(sorted);
      })
      .catch((e) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) setNeedsLogin(true);
        else setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, []);

  if (needsLogin) return <LoginPrompt next="/notifications" what="your notifications" />;

  return (
    <div>
      <h1>Notifications</h1>
      <ErrorText>{error}</ErrorText>
      {!items && !error && <p style={styles.muted}>Loading…</p>}
      {items && items.length === 0 && <p>No notifications yet.</p>}
      <ul style={{ listStyle: "none", padding: 0, display: "grid", gap: 8 }}>
        {items?.map((n) => (
          <li key={n.id} style={{ ...styles.card, background: n.read ? "#fff" : "#f0f6ff" }}>
            <div style={{ display: "flex", justifyContent: "space-between", gap: 8, flexWrap: "wrap" }}>
              <strong>{n.title}</strong>
              <span style={styles.muted}>{formatDate(n.createdAt)}</span>
            </div>
            <div style={{ marginTop: 4 }}>{n.body}</div>
            <div style={{ ...styles.muted, marginTop: 4 }}>
              {n.type}
              {!n.read && " · unread"}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
