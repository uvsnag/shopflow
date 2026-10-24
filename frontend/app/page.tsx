"use client";

// FR-04: product list with search, category filter and paging; FR-06: add to cart per item.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { addToCart, CATEGORIES, categoryName, errorMessage, formatVnd, getProducts, type ProductPage } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { EmptyState, ErrorText, Loading } from "@/components/ui";

const PAGE_SIZE = 12;

export default function ProductListPage() {
  const router = useRouter();
  const { user, ready } = useCurrentUser();
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [category, setCategory] = useState<number | "">("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ProductPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [added, setAdded] = useState<Record<number, string>>({});

  useEffect(() => {
    const id = setTimeout(() => setDebounced(query.trim()), 300);
    return () => clearTimeout(id);
  }, [query]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    getProducts(debounced, page, category, PAGE_SIZE)
      .then((d) => {
        if (!active) return;
        setData(d);
        setError(null);
      })
      .catch((e) => active && setError(errorMessage(e)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [debounced, category, page]);

  async function onAdd(productId: number) {
    if (!user) {
      router.push(`/login?next=${encodeURIComponent("/")}`);
      return;
    }
    setAdded((m) => ({ ...m, [productId]: "Adding…" }));
    try {
      await addToCart(productId, 1);
      setAdded((m) => ({ ...m, [productId]: "Added to cart" }));
    } catch (e) {
      setAdded((m) => ({ ...m, [productId]: errorMessage(e) }));
    }
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Products</h1>
          {data && <p className="subtitle">{data.totalItems} products</p>}
        </div>
      </div>

      <div className="toolbar">
        <input
          className="input grow"
          placeholder="Search products…"
          value={query}
          onChange={(e) => {
            setPage(0);
            setQuery(e.target.value);
          }}
        />
        <select
          className="input"
          value={category}
          onChange={(e) => {
            setPage(0);
            setCategory(e.target.value === "" ? "" : Number(e.target.value));
          }}
        >
          <option value="">All categories</option>
          {CATEGORIES.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </select>
      </div>

      <ErrorText>{error}</ErrorText>
      {loading && !data && <Loading />}
      {data && data.items.length === 0 && (
        <EmptyState title="No products found">Try another search or category.</EmptyState>
      )}

      <ul className="product-grid">
        {data?.items.map((p) => (
          <li key={p.id} className="product-card">
            <Link href={`/products/${p.id}`} className="product-media" aria-label={p.name}>
              {p.imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={p.imageUrl} alt={p.name} />
              ) : (
                <div className="product-placeholder" aria-hidden="true">
                  <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <rect x="3" y="5" width="18" height="14" rx="2" />
                    <path d="m3 16 5-5 4 4 3-3 6 6" />
                    <circle cx="16" cy="9" r="1.5" />
                  </svg>
                </div>
              )}
            </Link>
            <div className="product-body">
              <span className="product-category">{categoryName(p.categoryId)}</span>
              <Link href={`/products/${p.id}`} className="product-name">{p.name}</Link>
              <span className="product-price">{formatVnd(p.price)}</span>
              <button
                className="btn btn-primary"
                onClick={() => onAdd(p.id)}
                disabled={!ready || added[p.id] === "Adding…"}
                title={ready && !user ? "Log in to add to cart" : undefined}
              >
                {ready && !user ? "Log in to add" : added[p.id] === "Adding…" ? "Adding…" : "Add to cart"}
              </button>
              {added[p.id] && added[p.id] !== "Adding…" && (
                <span className={`hint ${added[p.id] === "Added to cart" ? "hint-ok" : "hint-error"}`}>
                  {added[p.id]}
                </span>
              )}
            </div>
          </li>
        ))}
      </ul>

      <div className="pagination">
        <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {data ? Math.max(data.totalPages, 1) : "…"}</span>
        <button className="btn btn-sm" disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}
