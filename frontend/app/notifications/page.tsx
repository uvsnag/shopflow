"use client";

// FR-11: in-app notifications, newest first.

import { useEffect, useState } from "react";
import { ApiError, errorMessage, getNotifications, type Notification } from "@/lib/api";
import { EmptyState, ErrorText, formatDate, Loading, LoginPrompt } from "@/components/ui";

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

  const unread = items?.filter((n) => !n.read).length ?? 0;

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Notifications</h1>
          {items && items.length > 0 && (
            <p className="subtitle">{unread > 0 ? `${unread} unread` : "All caught up"}</p>
          )}
        </div>
      </div>
      <ErrorText>{error}</ErrorText>
      {!items && !error && <Loading />}
      {items && items.length === 0 && (
        <EmptyState title="No notifications yet">Order and payment updates will appear here.</EmptyState>
      )}
      <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0, gap: 10 }}>
        {items?.map((n) => (
          <li key={n.id} className={`card card-sm notification${n.read ? "" : " unread"}`}>
            <div className="row row-between">
              <span className="title">{n.title}</span>
              <span className="muted">{formatDate(n.createdAt)}</span>
            </div>
            <div>{n.body}</div>
            <div className="meta">
              <span>{n.type}</span>
              {!n.read && <span>· unread</span>}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
