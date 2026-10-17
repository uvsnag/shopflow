"use client";

// FR-06 + FR-07: cart and checkout. Rough skeleton —
// TODO: quantity editing, product names (join with product API), payment step.

import { useEffect, useState } from "react";
import { api, placeOrder } from "@/lib/api";

interface CartItem { productId: number; quantity: number }
interface Cart { items: CartItem[] }

export default function CartPage() {
  const [cart, setCart] = useState<Cart | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    api<Cart>("/cart").then(setCart).catch(() => setMessage("Log in to see your cart."));
  }, []);

  async function checkout() {
    try {
      const order = await placeOrder();
      setMessage(`Order placed: ${JSON.stringify(order)}`);
      setCart({ items: [] });
    } catch (e) {
      setMessage(String(e));
    }
  }

  return (
    <div>
      <h1>Cart</h1>
      {message && <p>{message}</p>}
      <ul>
        {cart?.items.map((i) => (
          <li key={i.productId}>Product #{i.productId} × {i.quantity}</li>
        ))}
      </ul>
      <button onClick={checkout} disabled={!cart || cart.items.length === 0}>Place order</button>
    </div>
  );
}
