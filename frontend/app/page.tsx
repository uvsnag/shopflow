"use client";

// FR-04: product list with search, category filter and paging; FR-06: add to cart per item.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { addToCart, CATEGORIES, categoryName, errorMessage, formatVnd, getProducts, type ProductPage } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { ErrorText, styles } from "@/components/ui";

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
      <h1>Products</h1>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 12 }}>
        <input
          style={{ ...styles.input, flex: "1 1 200px" }}
          placeholder="Search products…"
          value={query}
          onChange={(e) => {
            setPage(0);
            setQuery(e.target.value);
          }}
        />
        <select
          style={styles.input}
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
      {loading && !data && <p style={styles.muted}>Loading…</p>}
      {data && data.items.length === 0 && <p>No products found.</p>}

      <ul
        style={{
          listStyle: "none",
          padding: 0,
          display: "grid",
          gap: 12,
          gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))",
        }}
      >
        {data?.items.map((p) => (
          <li key={p.id} style={{ ...styles.card, display: "flex", flexDirection: "column", gap: 6 }}>
            {p.imageUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={p.imageUrl} alt={p.name} style={{ width: "100%", height: 120, objectFit: "cover", borderRadius: 4 }} />
            ) : (
              <div style={{ height: 120, background: "#f0f0f0", borderRadius: 4 }} />
            )}
            <Link href={`/products/${p.id}`} style={{ fontWeight: 600 }}>{p.name}</Link>
            <span style={styles.muted}>{categoryName(p.categoryId)}</span>
            <span>{formatVnd(p.price)}</span>
            <button
              style={styles.primary}
              onClick={() => onAdd(p.id)}
              disabled={!ready || added[p.id] === "Adding…"}
              title={ready && !user ? "Log in to add to cart" : undefined}
            >
              {ready && !user ? "Log in to add" : "Add to cart"}
            </button>
            {added[p.id] && added[p.id] !== "Adding…" && (
              <span style={{ fontSize: 13, color: added[p.id] === "Added to cart" ? "#065f46" : "crimson" }}>
                {added[p.id]}
              </span>
            )}
          </li>
        ))}
      </ul>

      <div style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 12 }}>
        <button style={styles.button} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {data ? Math.max(data.totalPages, 1) : "…"}</span>
        <button style={styles.button} disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        {data && <span style={styles.muted}>{data.totalItems} products</span>}
      </div>
    </div>
  );
}
