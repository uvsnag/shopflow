"use client";

// Tiny shared UI bits (inline styles, no UI library): status badge, error text,
// login prompt and a few style constants reused across pages.

import Link from "next/link";
import type { CSSProperties } from "react";
import type { OrderStatus } from "@/lib/api";

export const styles = {
  input: { padding: "6px 8px", border: "1px solid #bbb", borderRadius: 4, fontSize: 14 } as CSSProperties,
  button: {
    padding: "6px 12px",
    border: "1px solid #888",
    borderRadius: 4,
    background: "#f5f5f5",
    cursor: "pointer",
    fontSize: 14,
  } as CSSProperties,
  primary: {
    padding: "6px 12px",
    border: "1px solid #1d4ed8",
    borderRadius: 4,
    background: "#2563eb",
    color: "#fff",
    cursor: "pointer",
    fontSize: 14,
  } as CSSProperties,
  card: { border: "1px solid #ddd", borderRadius: 6, padding: 12 } as CSSProperties,
  table: { width: "100%", borderCollapse: "collapse", fontSize: 14 } as CSSProperties,
  cell: { padding: "6px 8px", borderBottom: "1px solid #e5e5e5", textAlign: "left", verticalAlign: "top" } as CSSProperties,
  muted: { color: "#666", fontSize: 13 } as CSSProperties,
};

const STATUS_COLORS: Record<OrderStatus, { bg: string; fg: string }> = {
  PENDING: { bg: "#fef3c7", fg: "#92400e" },
  STOCK_RESERVED: { bg: "#dbeafe", fg: "#1e40af" },
  PAID: { bg: "#d1fae5", fg: "#065f46" },
  SHIPPED: { bg: "#e0e7ff", fg: "#3730a3" },
  DELIVERED: { bg: "#dcfce7", fg: "#14532d" },
  CANCELLED: { bg: "#fee2e2", fg: "#991b1b" },
  REFUNDED: { bg: "#f3e8ff", fg: "#6b21a8" },
};

export function StatusBadge({ status }: { status: OrderStatus | string }) {
  const c = STATUS_COLORS[status as OrderStatus] ?? { bg: "#eee", fg: "#333" };
  return (
    <span
      style={{
        display: "inline-block",
        padding: "2px 8px",
        borderRadius: 999,
        fontSize: 12,
        fontWeight: 600,
        background: c.bg,
        color: c.fg,
        whiteSpace: "nowrap",
      }}
    >
      {status.replace("_", " ")}
    </span>
  );
}

export function ErrorText({ children }: { children: React.ReactNode }) {
  if (!children) return null;
  return <p style={{ color: "crimson", margin: "8px 0" }}>{children}</p>;
}

export function InfoText({ children }: { children: React.ReactNode }) {
  if (!children) return null;
  return <p style={{ color: "#065f46", margin: "8px 0" }}>{children}</p>;
}

/** Shown on pages that need a session when the user is logged out. */
export function LoginPrompt({ next, what = "this page" }: { next: string; what?: string }) {
  return (
    <p>
      <Link href={`/login?next=${encodeURIComponent(next)}`}>Log in</Link> to see {what}.
    </p>
  );
}

export const formatDate = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
};
