"use client";

// FR-05: product detail with price, description and stock left; FR-06: add N to cart.

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { addToCart, ApiError, categoryName, errorMessage, formatVnd, getProduct, getStock, type Product } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { ErrorText, InfoText, Loading } from "@/components/ui";

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
        <Link href="/" className="back-link">← Products</Link>
        <ErrorText>{error}</ErrorText>
      </div>
    );
  }
  if (!product) return <Loading />;

  const outOfStock = available !== null && available <= 0;

  return (
    <div>
      <Link href="/" className="back-link">← Products</Link>
      <div className="product-detail">
        <div className="product-media">
          {product.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={product.imageUrl} alt={product.name} />
          ) : (
            <div className="product-placeholder" aria-hidden="true">
              <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="m3 16 5-5 4 4 3-3 6 6" />
                <circle cx="16" cy="9" r="1.5" />
              </svg>
            </div>
          )}
        </div>
        <div>
          <span className="product-category">{categoryName(product.categoryId)}</span>
          <h1>{product.name}</h1>
          <p className="price">{formatVnd(product.price)}</p>
          {product.description && <p className="description">{product.description}</p>}
          <p className="stock">
            {available === null ? (
              <span className="muted">Checking stock…</span>
            ) : outOfStock ? (
              <span className="stock-out">Out of stock</span>
            ) : (
              <span className="stock-ok">✓ {available} in stock</span>
            )}
          </p>
          <div className="row">
            <label className="row" style={{ gap: 8 }}>
              <span className="field-label">Qty</span>
              <input
                type="number"
                min={1}
                max={available ?? undefined}
                value={quantity}
                onChange={(e) => setQuantity(Math.max(1, Number(e.target.value) || 1))}
                className="input input-inline"
                style={{ width: 80 }}
                disabled={outOfStock}
              />
            </label>
            <button className="btn btn-primary" onClick={onAdd} disabled={!ready || busy || outOfStock}>
              {ready && !user ? "Log in to add to cart" : busy ? "Adding…" : "Add to cart"}
            </button>
          </div>
          <div style={{ marginTop: 16 }}>
            <InfoText>{notice}</InfoText>
            <ErrorText>{error}</ErrorText>
            {notice && (
              <Link href="/cart" className="btn">Go to cart →</Link>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
