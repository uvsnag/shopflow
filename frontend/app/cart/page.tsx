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
import { EmptyState, ErrorText, Loading, LoginPrompt } from "@/components/ui";

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
  const count = cart?.items.reduce((n, i) => n + i.quantity, 0) ?? 0;

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Cart</h1>
          {cart && cart.items.length > 0 && <p className="subtitle">{count} item{count === 1 ? "" : "s"}</p>}
        </div>
        <Link href="/" className="btn btn-ghost">← Continue shopping</Link>
      </div>
      <ErrorText>{error}</ErrorText>
      {!cart && !error && <Loading />}
      {cart && cart.items.length === 0 && (
        <EmptyState title="Your cart is empty">
          <p>Add a few products and come back here to place your order.</p>
          <Link href="/" className="btn btn-primary">Browse products</Link>
        </EmptyState>
      )}
      {cart && cart.items.length > 0 && (
        <div className="summary">
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Price</th>
                  <th>Quantity</th>
                  <th className="num">Subtotal</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {cart.items.map((i) => {
                  const p = products[i.productId];
                  const rowBusy = busy === i.productId;
                  return (
                    <tr key={i.productId}>
                      <td>
                        {p ? (
                          <Link href={`/products/${p.id}`}>{p.name}</Link>
                        ) : p === null ? (
                          <span className="muted">Product #{i.productId} (no longer available)</span>
                        ) : (
                          <span className="muted">Product #{i.productId}…</span>
                        )}
                      </td>
                      <td className="num">{p ? formatVnd(p.price) : "—"}</td>
                      <td className="nowrap">
                        <span className="qty">
                          <button type="button" aria-label="Decrease" disabled={rowBusy} onClick={() => changeQuantity(i.productId, i.quantity - 1)}>−</button>
                          <input
                            type="number"
                            min={0}
                            value={i.quantity}
                            disabled={rowBusy}
                            aria-label="Quantity"
                            onChange={(e) => {
                              const q = Number(e.target.value);
                              if (Number.isInteger(q) && q >= 0) changeQuantity(i.productId, q);
                            }}
                          />
                          <button type="button" aria-label="Increase" disabled={rowBusy} onClick={() => changeQuantity(i.productId, i.quantity + 1)}>+</button>
                        </span>
                      </td>
                      <td className="num">{p ? formatVnd(p.price * i.quantity) : "—"}</td>
                      <td className="actions">
                        <button className="btn btn-ghost btn-sm" disabled={rowBusy} onClick={() => changeQuantity(i.productId, 0)}>Remove</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="card">
            <h2>Order summary</h2>
            <ul className="summary-list">
              <li><span>Items</span><span>{count}</span></li>
              <li><span>Shipping</span><span className="muted">Free</span></li>
            </ul>
            <div className="summary-total"><span>Total</span><span>{formatVnd(total)}</span></div>
            <button
              className="btn btn-primary btn-block"
              style={{ marginTop: 16 }}
              onClick={checkout}
              disabled={!cart || cart.items.length === 0 || busy !== null}
            >
              {busy === "order" ? "Placing order…" : "Place order"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
