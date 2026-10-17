"use client";

// FR-12: create / edit / hide products; FR-13: add stock. The list reuses the public GET /products
// (so hidden products disappear from it) and shows stock from GET /inventory/{id}.

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  adminAddStock,
  adminCreateProduct,
  adminDeleteProduct,
  adminUpdateProduct,
  CATEGORIES,
  categoryName,
  errorMessage,
  formatVnd,
  getProducts,
  getStock,
  type Product,
  type ProductInput,
} from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";
import { ErrorText, InfoText, styles } from "@/components/ui";

const emptyForm = { categoryId: "1", name: "", description: "", price: "", imageUrl: "", active: true };
type FormState = typeof emptyForm;

export default function AdminProductsPage() {
  const { user, ready } = useCurrentUser();
  const [products, setProducts] = useState<Product[] | null>(null);
  const [stock, setStock] = useState<Record<number, number>>({});
  const [form, setForm] = useState<FormState>(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [formNotice, setFormNotice] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [stockQty, setStockQty] = useState<Record<number, string>>({});
  const [rowMessage, setRowMessage] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState<number | "form" | null>(null);

  const isAdmin = ready && user?.role === "ADMIN";

  const load = useCallback(async () => {
    try {
      const page = await getProducts("", 0, "", 100);
      setProducts(page.items);
      setListError(null);
      const stocks = await Promise.all(page.items.map((p) => getStock(p.id).catch(() => ({ productId: p.id, available: 0 }))));
      setStock(Object.fromEntries(stocks.map((s) => [s.productId, s.available])));
    } catch (e) {
      setListError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    if (isAdmin) load();
  }, [isAdmin, load]);

  function startEdit(p: Product) {
    setEditingId(p.id);
    setForm({
      categoryId: String(p.categoryId),
      name: p.name,
      description: p.description ?? "",
      price: String(p.price),
      imageUrl: p.imageUrl ?? "",
      active: p.active,
    });
    setFormError(null);
    setFormNotice(null);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function resetForm() {
    setEditingId(null);
    setForm(emptyForm);
    setFormError(null);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setFormNotice(null);
    const price = Number(form.price);
    if (form.name.trim().length < 2) return setFormError("Name is required.");
    if (!Number.isInteger(price) || price < 0) return setFormError("Price must be a whole number of VND.");
    const input: ProductInput = {
      categoryId: Number(form.categoryId),
      name: form.name.trim(),
      description: form.description.trim(),
      price,
      imageUrl: form.imageUrl.trim() || null,
      active: form.active,
    };
    setBusy("form");
    try {
      if (editingId === null) {
        const created = await adminCreateProduct(input);
        setFormNotice(`Created product #${created.id} "${created.name}".`);
      } else {
        const updated = await adminUpdateProduct(editingId, input);
        setFormNotice(`Updated product #${updated.id}.`);
      }
      resetForm();
      await load();
    } catch (err) {
      setFormError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  }

  async function onAddStock(p: Product) {
    const qty = Number(stockQty[p.id]);
    if (!Number.isInteger(qty) || qty <= 0) {
      setRowMessage((m) => ({ ...m, [p.id]: "Enter a positive whole quantity." }));
      return;
    }
    setBusy(p.id);
    try {
      const s = await adminAddStock(p.id, qty);
      setStock((m) => ({ ...m, [p.id]: s.available }));
      setStockQty((m) => ({ ...m, [p.id]: "" }));
      setRowMessage((m) => ({ ...m, [p.id]: `Stock: ${s.available} available, ${s.reserved} reserved.` }));
    } catch (e) {
      setRowMessage((m) => ({ ...m, [p.id]: errorMessage(e) }));
    } finally {
      setBusy(null);
    }
  }

  async function onHide(p: Product) {
    if (!window.confirm(`Hide "${p.name}" from the shop?`)) return;
    setBusy(p.id);
    try {
      await adminDeleteProduct(p.id);
      setProducts((list) => list?.filter((x) => x.id !== p.id) ?? null);
    } catch (e) {
      setRowMessage((m) => ({ ...m, [p.id]: errorMessage(e) }));
    } finally {
      setBusy(null);
    }
  }

  if (!ready) return <p style={styles.muted}>Loading…</p>;
  if (!isAdmin) {
    return (
      <div>
        <h1>Admin products</h1>
        <p>
          Admin only.{" "}
          {!user && <Link href={`/login?next=${encodeURIComponent("/admin/products")}`}>Log in as an admin</Link>}
        </p>
      </div>
    );
  }

  const set = (key: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value }));

  return (
    <div>
      <h1>Admin products</h1>

      <form onSubmit={onSubmit} style={{ ...styles.card, display: "grid", gap: 8, maxWidth: 480, marginBottom: 24 }}>
        <h2 style={{ margin: 0, fontSize: 18 }}>{editingId === null ? "Create product" : `Edit product #${editingId}`}</h2>
        <label style={{ display: "grid", gap: 4 }}>
          Category
          <select style={styles.input} value={form.categoryId} onChange={set("categoryId")}>
            {CATEGORIES.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </label>
        <label style={{ display: "grid", gap: 4 }}>
          Name
          <input style={styles.input} value={form.name} onChange={set("name")} required />
        </label>
        <label style={{ display: "grid", gap: 4 }}>
          Description
          <textarea style={{ ...styles.input, minHeight: 60 }} value={form.description} onChange={set("description")} />
        </label>
        <label style={{ display: "grid", gap: 4 }}>
          Price (VND)
          <input style={styles.input} type="number" min={0} step={1} value={form.price} onChange={set("price")} required />
        </label>
        <label style={{ display: "grid", gap: 4 }}>
          Image URL (optional)
          <input style={styles.input} value={form.imageUrl} onChange={set("imageUrl")} />
        </label>
        <label>
          <input type="checkbox" checked={form.active} onChange={set("active")} /> Active (visible in the shop)
        </label>
        <div style={{ display: "flex", gap: 8 }}>
          <button type="submit" style={styles.primary} disabled={busy === "form"}>
            {busy === "form" ? "Saving…" : editingId === null ? "Create" : "Save"}
          </button>
          {editingId !== null && (
            <button type="button" style={styles.button} onClick={resetForm}>Cancel edit</button>
          )}
        </div>
        <ErrorText>{formError}</ErrorText>
        <InfoText>{formNotice}</InfoText>
      </form>

      <h2 style={{ fontSize: 18 }}>Products</h2>
      <ErrorText>{listError}</ErrorText>
      {!products && !listError && <p style={styles.muted}>Loading…</p>}
      {products && products.length === 0 && <p>No visible products.</p>}
      {products && products.length > 0 && (
        <div style={{ overflowX: "auto" }}>
          <table style={styles.table}>
            <thead>
              <tr>
                <th style={styles.cell}>ID</th>
                <th style={styles.cell}>Name</th>
                <th style={styles.cell}>Category</th>
                <th style={styles.cell}>Price</th>
                <th style={styles.cell}>Stock</th>
                <th style={styles.cell}>Add stock</th>
                <th style={styles.cell}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.id}>
                  <td style={styles.cell}>{p.id}</td>
                  <td style={styles.cell}>
                    <Link href={`/products/${p.id}`}>{p.name}</Link>
                    {rowMessage[p.id] && (
                      <div style={{ fontSize: 12, color: rowMessage[p.id].startsWith("Stock:") ? "#065f46" : "crimson" }}>
                        {rowMessage[p.id]}
                      </div>
                    )}
                  </td>
                  <td style={styles.cell}>{categoryName(p.categoryId)}</td>
                  <td style={styles.cell}>{formatVnd(p.price)}</td>
                  <td style={styles.cell}>{stock[p.id] ?? "…"}</td>
                  <td style={{ ...styles.cell, whiteSpace: "nowrap" }}>
                    <input
                      type="number"
                      min={1}
                      placeholder="qty"
                      value={stockQty[p.id] ?? ""}
                      onChange={(e) => setStockQty((m) => ({ ...m, [p.id]: e.target.value }))}
                      style={{ ...styles.input, width: 70, marginRight: 6 }}
                    />
                    <button style={styles.button} disabled={busy === p.id} onClick={() => onAddStock(p)}>Add</button>
                  </td>
                  <td style={{ ...styles.cell, whiteSpace: "nowrap" }}>
                    <button style={styles.button} disabled={busy === p.id} onClick={() => startEdit(p)}>Edit</button>{" "}
                    <button style={styles.button} disabled={busy === p.id} onClick={() => onHide(p)}>Hide</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
