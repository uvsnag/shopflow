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
import { EmptyState, ErrorText, formatDate, Loading, StatusBadge } from "@/components/ui";

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

  if (!ready) return <Loading />;
  if (!isAdmin) {
    return (
      <EmptyState title="Admin only">
        <p>This page is restricted to administrators.</p>
        {!user && (
          <Link href={`/login?next=${encodeURIComponent("/admin/orders")}`} className="btn btn-primary">
            Log in as an admin
          </Link>
        )}
      </EmptyState>
    );
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Admin orders</h1>
          <p className="subtitle">Ship paid orders, then mark them delivered.</p>
        </div>
        <button className="btn" onClick={() => setReload((n) => n + 1)}>Refresh</button>
      </div>
      <div className="toolbar">
        <label>
          Status
          <select
            className="input"
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.target.value as OrderStatus | "");
            }}
          >
            <option value="">All</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>{s.replace("_", " ")}</option>
            ))}
          </select>
        </label>
      </div>
      <ErrorText>{error}</ErrorText>
      {!orders && !error && <Loading />}
      {orders && orders.length === 0 && <EmptyState title="No orders">Nothing matches this filter.</EmptyState>}
      {orders && orders.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Placed</th>
                <th>Status</th>
                <th>Items</th>
                <th className="num">Total</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td><strong>#{o.id}</strong></td>
                  <td className="nowrap">{formatDate(o.createdAt)}</td>
                  <td><StatusBadge status={o.status} /></td>
                  <td>
                    {o.items.map((i) => (
                      <div key={i.productId} className="line">{i.productName} <span className="muted">× {i.quantity}</span></div>
                    ))}
                  </td>
                  <td className="num">{formatVnd(o.totalAmount, o.currency)}</td>
                  <td className="actions">
                    <button
                      className="btn btn-sm"
                      disabled={o.status !== "PAID" || busy === o.id}
                      onClick={() => act(o, "ship")}
                    >
                      Ship
                    </button>
                    <button
                      className="btn btn-sm"
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
      <div className="pagination">
        <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1}</span>
        <button className="btn btn-sm" disabled={!orders || orders.length < PAGE_SIZE} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}
