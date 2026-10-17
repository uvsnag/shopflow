"use client";

// FR-07 + FR-08: checkout — review a placed order, then pay.
// Rough skeleton — TODO: poll order status after paying (webhook settles async).

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { getOrder, payOrder, type Order } from "@/lib/api";

function Checkout() {
  const orderId = useSearchParams().get("orderId");
  const [order, setOrder] = useState<Order | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [paying, setPaying] = useState(false);

  useEffect(() => {
    if (!orderId) return;
    getOrder(orderId).then(setOrder).catch((e) => setMessage(String(e)));
  }, [orderId]);

  async function onPay() {
    if (!order) return;
    setPaying(true);
    try {
      const result = await payOrder(order.id, order.totalAmount);
      setMessage(`Payment #${result.paymentId} is ${result.status}. See "My orders" for the final status.`);
    } catch (e) {
      setMessage(String(e));
      setPaying(false);
    }
  }

  if (!orderId) return <p>No order selected. Place an order from your cart first.</p>;

  return (
    <div>
      <h1>Checkout</h1>
      {message && <p>{message}</p>}
      {order && (
        <>
          <p>Order #{order.id} — <strong>{order.status}</strong></p>
          <ul>
            {order.items.map((i) => (
              <li key={i.productId}>
                {i.productName} × {i.quantity} — {i.unitPrice.toLocaleString()} {order.currency}
              </li>
            ))}
          </ul>
          <p><strong>Total: {order.totalAmount.toLocaleString()} {order.currency}</strong></p>
          <button
            onClick={onPay}
            disabled={paying || !["PENDING", "STOCK_RESERVED"].includes(order.status)}
          >
            Pay
          </button>
        </>
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
