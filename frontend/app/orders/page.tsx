"use client";

// FR-09: my orders with status. Rough skeleton — TODO: detail view, cancel button, pay button.

import { useEffect, useState } from "react";
import { api } from "@/lib/api";

interface OrderItem { productName: string; unitPrice: number; quantity: number }
interface Order { id: number; status: string; totalAmount: number; currency: string; items: OrderItem[] }

export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Order[]>("/orders").then(setOrders).catch(() => setError("Log in to see your orders."));
  }, []);

  if (error) return <p>{error}</p>;

  return (
    <div>
      <h1>My orders</h1>
      {orders.length === 0 && <p>No orders yet.</p>}
      <ul>
        {orders.map((o) => (
          <li key={o.id}>
            Order #{o.id} — <strong>{o.status}</strong> — {o.totalAmount.toLocaleString()} {o.currency}
          </li>
        ))}
      </ul>
    </div>
  );
}
