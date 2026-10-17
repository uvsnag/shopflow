"use client";

// FR-06: cart with product names/prices, quantity editing and removal; FR-07: place order.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  errorMessage,
  formatVnd,
  getCart,
  getProduct,
  placeOrder,
  removeCartItem,
  setCartItem,
  type Cart,
  type Product,
} from "@/lib/api";
import { ErrorText, LoginPrompt, styles } from "@/components/ui";

type Products = Record<number, Product | null>; // null = product no longer available

export default function CartPage() {
  const router = useRouter();
  const [cart, setCart] = useState<Cart | null>(null);
  const [products, setProducts] = useState<Products>({});
  const [error, setError] = useState<string | null>(null);
  const [needsLogin, setNeedsLogin] = useState(false);
  const [busy, setBusy] = useState<number | "order" | null>(null);

  const loadProducts = useCallback(async (c: Cart, known: Products) => {
    const missing = c.items.map((i) => i.productId).filter((id) => !(id in known));
    if (missing.length === 0) return;
    const fetched = await Promise.all(
      missing.map((id) => getProduct(id).catch(() => null)),
    );
    setProducts((prev) => {
      const next = { ...prev };
      missing.forEach((id, idx) => {
        next[id] = fetched[idx];
      });
      return next;
    });
  }, []);

  useEffect(() => {
    let active = true;
    getCart()
      .then(async (c) => {
        if (!active) return;
        setCart(c);
        await loadProducts(c, {});
      })
      .catch((e) => {
        if (!active) return;
        if (e instanceof ApiError && e.status === 401) setNeedsLogin(true);
        else setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [loadProducts]);

  async function changeQuantity(productId: number, quantity: number) {
    setBusy(productId);
    setError(null);
    try {
      const c = quantity <= 0 ? await removeCartItem(productId) : await setCartItem(productId, quantity);
      setCart(c);
      await loadProducts(c, products);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function checkout() {
    setBusy("order");
    setError(null);
    try {
      const order = await placeOrder();
      setCart({ items: [], updatedAt: null });
      router.push(`/checkout?orderId=${order.id}`);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(null);
    }
  }

  if (needsLogin) return <LoginPrompt next="/cart" what="your cart" />;

  const total = cart?.items.reduce((sum, i) => sum + (products[i.productId]?.price ?? 0) * i.quantity, 0) ?? 0;

  return (
    <div>
      <h1>Cart</h1>
      <ErrorText>{error}</ErrorText>
      {!cart && !error && <p style={styles.muted}>Loading…</p>}
      {cart && cart.items.length === 0 && (
        <p>
          Your cart is empty. <Link href="/">Browse products</Link>
        </p>
      )}
      {cart && cart.items.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table style={styles.table}>
            <thead>
              <tr>
                <th style={styles.cell}>Product</th>
                <th style={styles.cell}>Price</th>
                <th style={styles.cell}>Quantity</th>
                <th style={styles.cell}>Subtotal</th>
                <th style={styles.cell} />
              </tr>
            </thead>
            <tbody>
              {cart.items.map((i) => {
                const p = products[i.productId];
                const rowBusy = busy === i.productId;
                return (
                  <tr key={i.productId}>
                    <td style={styles.cell}>
                      {p ? (
                        <Link href={`/products/${p.id}`}>{p.name}</Link>
                      ) : p === null ? (
                        <span style={styles.muted}>Product #{i.productId} (no longer available)</span>
                      ) : (
                        <span style={styles.muted}>Product #{i.productId}…</span>
                      )}
                    </td>
                    <td style={styles.cell}>{p ? formatVnd(p.price) : "—"}</td>
                    <td style={{ ...styles.cell, whiteSpace: "nowrap" }}>
                      <button style={styles.button} disabled={rowBusy} onClick={() => changeQuantity(i.productId, i.quantity - 1)}>−</button>
                      <input
                        type="number"
                        min={0}
                        value={i.quantity}
                        disabled={rowBusy}
                        onChange={(e) => {
                          const q = Number(e.target.value);
                          if (Number.isInteger(q) && q >= 0) changeQuantity(i.productId, q);
                        }}
                        style={{ ...styles.input, width: 60, margin: "0 6px", textAlign: "center" }}
                      />
                      <button style={styles.button} disabled={rowBusy} onClick={() => changeQuantity(i.productId, i.quantity + 1)}>+</button>
                    </td>
                    <td style={styles.cell}>{p ? formatVnd(p.price * i.quantity) : "—"}</td>
                    <td style={styles.cell}>
                      <button style={styles.button} disabled={rowBusy} onClick={() => changeQuantity(i.productId, 0)}>Remove</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr>
                <td style={styles.cell} colSpan={3}><strong>Total</strong></td>
                <td style={styles.cell} colSpan={2}><strong>{formatVnd(total)}</strong></td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      <p>
        <button
          style={styles.primary}
          onClick={checkout}
          disabled={!cart || cart.items.length === 0 || busy !== null}
        >
          {busy === "order" ? "Placing order…" : "Place order"}
        </button>
      </p>
    </div>
  );
}
