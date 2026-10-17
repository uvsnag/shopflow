"use client";

// FR-14: all orders filtered by status; FR-15: mark Shipped (from PAID) then Delivered (from SHIPPED).
// FR-16 (refund) is not offered here: the Order payload carries no paymentId.

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  adminDeliverOrder,
  adminGetOrders,
  adminShipOrder,
  errorMessage,
  formatVnd,
  ORDER_STATUSES,
  type Order,
  type OrderStatus,
} from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { ErrorText, formatDate, StatusBadge, styles } from "@/components/ui";

const PAGE_SIZE = 20;

export default function AdminOrdersPage() {
  const { user, ready } = useCurrentUser();
  const [status, setStatus] = useState<OrderStatus | "">("");
  const [page, setPage] = useState(0);
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [reload, setReload] = useState(0);

  const isAdmin = ready && user?.role === "ADMIN";

  useEffect(() => {
    if (!isAdmin) return;
    let active = true;
    adminGetOrders(status, page, PAGE_SIZE)
      .then((o) => {
        if (!active) return;
        setOrders(o);
        setError(null);
      })
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [isAdmin, status, page, reload]);

  async function act(order: Order, action: "ship" | "deliver") {
    setBusy(order.id);
    setError(null);
    try {
      const updated = action === "ship" ? await adminShipOrder(order.id) : await adminDeliverOrder(order.id);
      setOrders((list) => list?.map((o) => (o.id === updated.id ? updated : o)) ?? null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  if (!ready) return <p style={styles.muted}>Loading…</p>;
  if (!isAdmin) {
    return (
      <div>
        <h1>Admin orders</h1>
        <p>
          Admin only.{" "}
          {!user && <Link href={`/login?next=${encodeURIComponent("/admin/orders")}`}>Log in as an admin</Link>}
        </p>
      </div>
    );
  }

  return (
    <div>
      <h1>Admin orders</h1>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", marginBottom: 12 }}>
        <label>
          Status{" "}
          <select
            style={styles.input}
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.target.value as OrderStatus | "");
            }}
          >
            <option value="">All</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <button style={styles.button} onClick={() => setReload((n) => n + 1)}>Refresh</button>
      </div>
      <ErrorText>{error}</ErrorText>
      {!orders && !error && <p style={styles.muted}>Loading…</p>}
      {orders && orders.length === 0 && <p>No orders.</p>}
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
                <th style={styles.cell}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td style={styles.cell}>#{o.id}</td>
                  <td style={styles.cell}>{formatDate(o.createdAt)}</td>
                  <td style={styles.cell}><StatusBadge status={o.status} /></td>
                  <td style={styles.cell}>
                    {o.items.map((i) => (
                      <div key={i.productId}>{i.productName} × {i.quantity}</div>
                    ))}
                  </td>
                  <td style={styles.cell}>{formatVnd(o.totalAmount, o.currency)}</td>
                  <td style={{ ...styles.cell, whiteSpace: "nowrap" }}>
                    <button
                      style={styles.button}
                      disabled={o.status !== "PAID" || busy === o.id}
                      onClick={() => act(o, "ship")}
                    >
                      Ship
                    </button>{" "}
                    <button
                      style={styles.button}
                      disabled={o.status !== "SHIPPED" || busy === o.id}
                      onClick={() => act(o, "deliver")}
                    >
                      Deliver
                    </button>
                  </td>
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
