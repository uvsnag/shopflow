"use client";

// FR-04: product list with search and paging. Rough skeleton —
// TODO: category filter, product detail page, add-to-cart button per item.

import { useEffect, useState } from "react";
import { getProducts, type ProductPage } from "@/lib/api";

export default function ProductListPage() {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ProductPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getProducts(query, page).then(setData).catch((e) => setError(String(e)));
  }, [query, page]);

  return (
    <div>
      <h1>Products</h1>
      <input
        placeholder="Search products…"
        value={query}
        onChange={(e) => { setPage(0); setQuery(e.target.value); }}
      />
      {error && <p style={{ color: "crimson" }}>{error}</p>}
      <ul>
        {data?.items.map((p) => (
          <li key={p.id}>
            <strong>{p.name}</strong> — {p.price.toLocaleString()} VND
          </li>
        ))}
      </ul>
      <div style={{ display: "flex", gap: 8 }}>
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {data?.totalPages ?? "…"}</span>
        <button disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}
