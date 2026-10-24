"use client";

// FR-09: order detail (items, total, status); FR-08: "Pay now" when stock is reserved;
// FR-10: cancel while PENDING / STOCK_RESERVED / PAID (PAID becomes REFUNDED).

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { ApiError, CANCELLABLE_STATUSES, cancelOrder, errorMessage, formatVnd, getOrder, type Order } from "@/lib/api";
import { ErrorText, formatDate, InfoText, Loading, LoginPrompt, StatusBadge } from "@/components/ui";

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [needsLogin, setNeedsLogin] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      setOrder(await getOrder(id));
      setError(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setNeedsLogin(true);
      else if (e instanceof ApiError && e.status === 404) setError("Order not found.");
      else setError(errorMessage(e));
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  async function onCancel() {
    if (!order) return;
    const refund = order.status === "PAID";
    const ok = window.confirm(
      refund
        ? `Cancel order #${order.id}? The payment will be refunded.`
        : `Cancel order #${order.id}?`,
    );
    if (!ok) return;
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      const updated = await cancelOrder(order.id);
      setOrder(updated);
      setNotice(updated.status === "REFUNDED" ? "Order cancelled and refunded." : "Order cancelled.");
    } catch (e) {
      setError(errorMessage(e));
      await load();
    } finally {
      setBusy(false);
    }
  }

  if (needsLogin) return <LoginPrompt next={`/orders/${id}`} what="this order" />;
  if (error && !order) {
    return (
      <div>
        <Link href="/orders" className="back-link">← My orders</Link>
        <ErrorText>{error}</ErrorText>
      </div>
    );
  }
  if (!order) return <Loading />;

  const cancellable = CANCELLABLE_STATUSES.includes(order.status);

  return (
    <div>
      <Link href="/orders" className="back-link">← My orders</Link>
      <div className="page-header">
        <div>
          <h1 className="row" style={{ gap: 12 }}>
            Order #{order.id} <StatusBadge status={order.status} />
          </h1>
          <p className="subtitle">Placed {formatDate(order.createdAt)}</p>
        </div>
        <div className="row">
          {order.status === "STOCK_RESERVED" && (
            <Link href={`/checkout?orderId=${order.id}`} className="btn btn-primary">Pay now</Link>
          )}
          {cancellable && (
            <button className="btn btn-danger" onClick={onCancel} disabled={busy}>
              {busy ? "Cancelling…" : order.status === "PAID" ? "Cancel order (refund)" : "Cancel order"}
            </button>
          )}
          <button className="btn btn-ghost" onClick={load} disabled={busy}>Refresh</button>
        </div>
      </div>
      <ErrorText>{error}</ErrorText>
      <InfoText>{notice}</InfoText>
      {order.status === "PENDING" && (
        <div className="alert alert-note">Stock is being reserved. Refresh in a moment to pay.</div>
      )}

      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Product</th>
              <th className="num">Unit price</th>
              <th className="num">Qty</th>
              <th className="num">Subtotal</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((i) => (
              <tr key={i.productId}>
                <td><Link href={`/products/${i.productId}`}>{i.productName}</Link></td>
                <td className="num">{formatVnd(i.unitPrice, order.currency)}</td>
                <td className="num">{i.quantity}</td>
                <td className="num">{formatVnd(i.unitPrice * i.quantity, order.currency)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={3}>Total</td>
              <td className="num">{formatVnd(order.totalAmount, order.currency)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}
