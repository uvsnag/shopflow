"use client";

// Small shared UI bits on top of app/globals.css: status badge, alerts, loading and
// empty states, login prompt. Pages use the CSS classes directly for everything else.

import Link from "next/link";
import type { OrderStatus } from "@/lib/api";

export function StatusBadge({ status }: { status: OrderStatus | string }) {
  return <span className={`badge badge-${status}`}>{status.replace("_", " ")}</span>;
}

export function ErrorText({ children }: { children: React.ReactNode }) {
  if (!children) return null;
  return (
    <div className="alert alert-error" role="alert">
      {children}
    </div>
  );
}

export function InfoText({ children }: { children: React.ReactNode }) {
  if (!children) return null;
  return (
    <div className="alert alert-info" role="status">
      {children}
    </div>
  );
}

export function Loading({ children = "Loading…" }: { children?: React.ReactNode }) {
  return (
    <p className="loading">
      <span className="spinner" aria-hidden="true" /> {children}
    </p>
  );
}

export function EmptyState({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children}
    </div>
  );
}

/** Shown on pages that need a session when the user is logged out. */
export function LoginPrompt({ next, what = "this page" }: { next: string; what?: string }) {
  return (
    <EmptyState title="Please log in">
      <p>You need an account to see {what}.</p>
      <Link href={`/login?next=${encodeURIComponent(next)}`} className="btn btn-primary">
        Log in
      </Link>
    </EmptyState>
  );
}

export const formatDate = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
};
