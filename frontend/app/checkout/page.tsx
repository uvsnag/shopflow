"use client";

// FR-07 + FR-08: checkout — review a placed order, pay once stock is reserved, then poll
// GET /orders/{id} every 2 s until the payment settles (PAID / CANCELLED / REFUNDED, max ~60 s).

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import {
  ApiError,
  errorMessage,
  formatVnd,
  getOrder,
  payOrder,
  SETTLED_STATUSES,
  type Order,
} from "@/lib/api";
import { EmptyState, ErrorText, InfoText, Loading, LoginPrompt, StatusBadge } from "@/components/ui";

const POLL_MS = 2000;
const MAX_POLLS = 30; // ~60 s

function Checkout() {
  const orderId = useSearchParams().get("orderId");
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [needsLogin, setNeedsLogin] = useState(false);
  const [paying, setPaying] = useState(false);
  const [paid, setPaid] = useState(false); // a payment was accepted; keep polling until settled
  const [polling, setPolling] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  const [reload, setReload] = useState(0);

  // Poll the order: before paying until STOCK_RESERVED (or settled), after paying until settled.
  useEffect(() => {
    if (!orderId) return;
    let active = true;
    let polls = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setTimedOut(false);
    setPolling(true);

    const shouldStop = (o: Order) =>
      SETTLED_STATUSES.includes(o.status) || (!paid && o.status === "STOCK_RESERVED");

    const tick = async () => {
      try {
        const o = await getOrder(orderId);
        if (!active) return;
        setOrder(o);
        setError(null);
        if (shouldStop(o)) {
          setPolling(false);
          return;
        }
      } catch (e) {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) setNeedsLogin(true);
        else setError(errorMessage(e));
        setPolling(false);
        return;
      }
      polls += 1;
      if (polls >= MAX_POLLS) {
        setTimedOut(true);
        setPolling(false);
        return;
      }
      timer = setTimeout(tick, POLL_MS);
    };
    tick();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [orderId, paid, reload]);

  async function onPay() {
    if (!order) return;
    setPaying(true);
    setError(null);
    setNotice(null);
    try {
      const result = await payOrder(order.id);
      setNotice(`Payment #${result.paymentId} accepted (${result.status}). Waiting for the provider to confirm…`);
      setPaid(true);
    } catch (e) {
      setError(errorMessage(e)); // 409: not ready / cancelled / already paid — the detail says which
      try {
        setOrder(await getOrder(order.id));
      } catch {
        // keep what we have
      }
    } finally {
      setPaying(false);
    }
  }

  if (!orderId) {
    return (
      <EmptyState title="No order selected">
        <p>Place an order from your cart first.</p>
        <Link href="/cart" className="btn btn-primary">Go to cart</Link>
      </EmptyState>
    );
  }
  if (needsLogin) return <LoginPrompt next={`/checkout?orderId=${orderId}`} what="this order" />;

  const status = order?.status;
  const canPay = status === "STOCK_RESERVED" && !paying && !paid;

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Checkout</h1>
          {order && <p className="subtitle">Order #{order.id}</p>}
        </div>
        {order && <StatusBadge status={order.status} />}
      </div>
      <ErrorText>{error}</ErrorText>
      {!order && !error && <Loading>Loading order…</Loading>}
      {order && (
        <div className="summary">
          <div className="card">
            <h2>Items</h2>
            <ul className="summary-list">
              {order.items.map((i) => (
                <li key={i.productId}>
                  <span>{i.productName} <span className="muted">× {i.quantity}</span></span>
                  <span>{formatVnd(i.unitPrice * i.quantity, order.currency)}</span>
                </li>
              ))}
            </ul>
            <div className="summary-total">
              <span>Total</span>
              <span>{formatVnd(order.totalAmount, order.currency)}</span>
            </div>
          </div>

          <div className="card">
            <h2>Payment</h2>
            {status === "PENDING" && (
              <div className="timeline-note">
                {polling && <span className="spinner" aria-hidden="true" />}
                <span>
                  {polling ? "Reserving stock…" : timedOut ? "Still waiting for stock reservation." : "Waiting for stock reservation."}
                </span>
                {!polling && (
                  <button className="btn btn-sm" onClick={() => setReload((n) => n + 1)}>Refresh</button>
                )}
              </div>
            )}
            {status === "STOCK_RESERVED" && !paid && (
              <div className="alert alert-note">Stock is reserved. You can pay now.</div>
            )}
            {paid && status === "STOCK_RESERVED" && (
              <div className="timeline-note">
                {polling && <span className="spinner" aria-hidden="true" />}
                <span>
                  {polling ? "Waiting for payment confirmation…" : timedOut ? "The payment has not settled yet. Check My orders later." : ""}
                </span>
              </div>
            )}
            {status === "PAID" && <InfoText>Payment confirmed. Thank you for your order!</InfoText>}
            {status === "CANCELLED" && <ErrorText>The order was cancelled{paid ? " (payment failed)" : ""}.</ErrorText>}
            {status === "REFUNDED" && <InfoText>This order was refunded.</InfoText>}
            {(status === "SHIPPED" || status === "DELIVERED") && <InfoText>This order is already paid and {status.toLowerCase()}.</InfoText>}

            <InfoText>{notice}</InfoText>

            <button className="btn btn-primary btn-block" onClick={onPay} disabled={!canPay}>
              {paying ? "Paying…" : `Pay ${formatVnd(order.totalAmount, order.currency)}`}
            </button>
            <div className="row" style={{ marginTop: 14, justifyContent: "center" }}>
              <Link href={`/orders/${order.id}`} className="btn btn-ghost btn-sm">Order details</Link>
              <Link href="/orders" className="btn btn-ghost btn-sm">My orders</Link>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default function CheckoutPage() {
  return (
    <Suspense>
      <Checkout />
    </Suspense>
  );
}
