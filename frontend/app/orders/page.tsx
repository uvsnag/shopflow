"use client";

// FR-09: my orders with status badge; each row links to the order detail.

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, errorMessage, formatVnd, getOrders, type Order } from "@/lib/api";
import { ErrorText, formatDate, LoginPrompt, StatusBadge, styles } from "@/components/ui";

const PAGE_SIZE = 20;

export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [needsLogin, setNeedsLogin] = useState(false);

  useEffect(() => {
    let active = true;
    getOrders(page, PAGE_SIZE)
      .then((o) => active && setOrders(o))
      .catch((e) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) setNeedsLogin(true);
        else setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [page]);

  if (needsLogin) return <LoginPrompt next="/orders" what="your orders" />;

  return (
    <div>
      <h1>My orders</h1>
      <ErrorText>{error}</ErrorText>
      {!orders && !error && <p style={styles.muted}>Loading…</p>}
      {orders && orders.length === 0 && page === 0 && (
        <p>
          No orders yet. <Link href="/">Browse products</Link>
        </p>
      )}
      {orders && orders.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table style={styles.table}>
            <thead>
              <tr>
                <th style={styles.cell}>Order</th>
                <th style={styles.cell}>Placed</th>
                <th style={styles.cell}>Status</th>
                <th style={styles.cell}>Items</th>
                <th style={styles.cell}>Total</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td style={styles.cell}><Link href={`/orders/${o.id}`}>#{o.id}</Link></td>
                  <td style={styles.cell}>{formatDate(o.createdAt)}</td>
                  <td style={styles.cell}><StatusBadge status={o.status} /></td>
                  <td style={styles.cell}>{o.items.reduce((n, i) => n + i.quantity, 0)}</td>
                  <td style={styles.cell}>{formatVnd(o.totalAmount, o.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 12 }}>
        <button style={styles.button} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1}</span>
        <button style={styles.button} disabled={!orders || orders.length < PAGE_SIZE} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}
