"use client";

// FR-09: my orders with status badge; each row links to the order detail.

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, errorMessage, formatVnd, getOrders, type Order } from "@/lib/api";
import { EmptyState, ErrorText, formatDate, Loading, LoginPrompt, StatusBadge } from "@/components/ui";

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
      <div className="page-header">
        <h1>My orders</h1>
      </div>
      <ErrorText>{error}</ErrorText>
      {!orders && !error && <Loading />}
      {orders && orders.length === 0 && page === 0 && (
        <EmptyState title="No orders yet">
          <p>Your orders will show up here once you place one.</p>
          <Link href="/" className="btn btn-primary">Browse products</Link>
        </EmptyState>
      )}
      {orders && orders.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Placed</th>
                <th>Status</th>
                <th className="num">Items</th>
                <th className="num">Total</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td><Link href={`/orders/${o.id}`}><strong>#{o.id}</strong></Link></td>
                  <td className="nowrap">{formatDate(o.createdAt)}</td>
                  <td><StatusBadge status={o.status} /></td>
                  <td className="num">{o.items.reduce((n, i) => n + i.quantity, 0)}</td>
                  <td className="num">{formatVnd(o.totalAmount, o.currency)}</td>
                  <td className="actions">
                    <Link href={`/orders/${o.id}`} className="btn btn-sm">View</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(page > 0 || (orders && orders.length > 0)) && (
        <div className="pagination">
          <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1}</span>
          <button className="btn btn-sm" disabled={!orders || orders.length < PAGE_SIZE} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </div>
  );
}
