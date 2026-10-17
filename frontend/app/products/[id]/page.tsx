"use client";

// FR-05: product detail with price, description and stock left; FR-06: add N to cart.

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { addToCart, ApiError, categoryName, errorMessage, formatVnd, getProduct, getStock, type Product } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { ErrorText, InfoText, styles } from "@/components/ui";

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { user, ready } = useCurrentUser();
  const [product, setProduct] = useState<Product | null>(null);
  const [available, setAvailable] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!id) return;
    let active = true;
    getProduct(id)
      .then((p) => active && setProduct(p))
      .catch((e) => {
        if (!active) return;
        setError(e instanceof ApiError && e.status === 404 ? "Product not found." : errorMessage(e));
      });
    getStock(id)
      .then((s) => active && setAvailable(s.available))
      .catch((e) => active && setError(errorMessage(e)));
    return () => {
      active = false;
    };
  }, [id]);

  async function onAdd() {
    if (!product) return;
    if (!user) {
      router.push(`/login?next=${encodeURIComponent(`/products/${product.id}`)}`);
      return;
    }
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      await addToCart(product.id, quantity);
      setNotice(`Added ${quantity} × ${product.name} to your cart.`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  if (error && !product) {
    return (
      <div>
        <ErrorText>{error}</ErrorText>
        <Link href="/">Back to products</Link>
      </div>
    );
  }
  if (!product) return <p style={styles.muted}>Loading…</p>;

  const outOfStock = available !== null && available <= 0;

  return (
    <div>
      <p><Link href="/">← Products</Link></p>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 24 }}>
        {product.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={product.imageUrl} alt={product.name} style={{ width: 280, maxWidth: "100%", borderRadius: 6, objectFit: "cover" }} />
        ) : (
          <div style={{ width: 280, maxWidth: "100%", height: 200, background: "#f0f0f0", borderRadius: 6 }} />
        )}
        <div style={{ flex: "1 1 260px" }}>
          <h1 style={{ marginTop: 0 }}>{product.name}</h1>
          <p style={styles.muted}>{categoryName(product.categoryId)}</p>
          <p style={{ fontSize: 20, fontWeight: 600 }}>{formatVnd(product.price)}</p>
          <p>{product.description}</p>
          <p>
            {available === null ? (
              <span style={styles.muted}>Checking stock…</span>
            ) : outOfStock ? (
              <strong style={{ color: "crimson" }}>Out of stock</strong>
            ) : (
              <span>{available} left in stock</span>
            )}
          </p>
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <label>
              Qty{" "}
              <input
                type="number"
                min={1}
                max={available ?? undefined}
                value={quantity}
                onChange={(e) => setQuantity(Math.max(1, Number(e.target.value) || 1))}
                style={{ ...styles.input, width: 72 }}
                disabled={outOfStock}
              />
            </label>
            <button style={styles.primary} onClick={onAdd} disabled={!ready || busy || outOfStock}>
              {ready && !user ? "Log in to add to cart" : "Add to cart"}
            </button>
          </div>
          <InfoText>{notice}</InfoText>
          <ErrorText>{error}</ErrorText>
          {notice && <p><Link href="/cart">Go to cart</Link></p>}
        </div>
      </div>
    </div>
  );
}
