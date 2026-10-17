"use client";

// FR-09: order detail (items, total, status); FR-08: "Pay now" when stock is reserved;
// FR-10: cancel while PENDING / STOCK_RESERVED / PAID (PAID becomes REFUNDED).

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { ApiError, CANCELLABLE_STATUSES, cancelOrder, errorMessage, formatVnd, getOrder, type Order } from "@/lib/api";
import { ErrorText, formatDate, InfoText, LoginPrompt, StatusBadge, styles } from "@/components/ui";

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
        <ErrorText>{error}</ErrorText>
        <Link href="/orders">← My orders</Link>
      </div>
    );
  }
  if (!order) return <p style={styles.muted}>Loading…</p>;

  const cancellable = CANCELLABLE_STATUSES.includes(order.status);

  return (
    <div>
      <p><Link href="/orders">← My orders</Link></p>
      <h1 style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        Order #{order.id} <StatusBadge status={order.status} />
      </h1>
      <p style={styles.muted}>Placed {formatDate(order.createdAt)}</p>
      <ErrorText>{error}</ErrorText>
      <InfoText>{notice}</InfoText>

      <div style={{ overflowX: "auto" }}>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.cell}>Product</th>
              <th style={styles.cell}>Unit price</th>
              <th style={styles.cell}>Qty</th>
              <th style={styles.cell}>Subtotal</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((i) => (
              <tr key={i.productId}>
                <td style={styles.cell}><Link href={`/products/${i.productId}`}>{i.productName}</Link></td>
                <td style={styles.cell}>{formatVnd(i.unitPrice, order.currency)}</td>
                <td style={styles.cell}>{i.quantity}</td>
                <td style={styles.cell}>{formatVnd(i.unitPrice * i.quantity, order.currency)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td style={styles.cell} colSpan={3}><strong>Total</strong></td>
              <td style={styles.cell}><strong>{formatVnd(order.totalAmount, order.currency)}</strong></td>
            </tr>
          </tfoot>
        </table>
      </div>

      {order.status === "PENDING" && <p style={styles.muted}>Stock is being reserved. Refresh in a moment to pay.</p>}

      <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", marginTop: 12 }}>
        {order.status === "STOCK_RESERVED" && (
          <Link href={`/checkout?orderId=${order.id}`} style={{ ...styles.primary, textDecoration: "none", display: "inline-block" }}>
            Pay now
          </Link>
        )}
        {cancellable && (
          <button style={styles.button} onClick={onCancel} disabled={busy}>
            {busy ? "Cancelling…" : order.status === "PAID" ? "Cancel order (refund)" : "Cancel order"}
          </button>
        )}
        <button style={styles.button} onClick={load} disabled={busy}>Refresh</button>
      </div>
    </div>
  );
}
