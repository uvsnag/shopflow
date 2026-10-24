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
import { EmptyState, ErrorText, InfoText, Loading } from "@/components/ui";

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

  if (!ready) return <Loading />;
  if (!isAdmin) {
    return (
      <EmptyState title="Admin only">
        <p>This page is restricted to administrators.</p>
        {!user && (
          <Link href={`/login?next=${encodeURIComponent("/admin/products")}`} className="btn btn-primary">
            Log in as an admin
          </Link>
        )}
      </EmptyState>
    );
  }

  const set = (key: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value }));

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>Admin products</h1>
          <p className="subtitle">Create and edit products, manage stock.</p>
        </div>
      </div>

      <form onSubmit={onSubmit} className="card stack" style={{ maxWidth: 560 }}>
        <h2 style={{ margin: 0 }}>{editingId === null ? "Create product" : `Edit product #${editingId}`}</h2>
        <ErrorText>{formError}</ErrorText>
        <InfoText>{formNotice}</InfoText>
        <label className="field">
          <span>Category</span>
          <select className="input" value={form.categoryId} onChange={set("categoryId")}>
            {CATEGORIES.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Name</span>
          <input className="input" value={form.name} onChange={set("name")} required />
        </label>
        <label className="field">
          <span>Description</span>
          <textarea className="input" value={form.description} onChange={set("description")} />
        </label>
        <label className="field">
          <span>Price (VND)</span>
          <input className="input" type="number" min={0} step={1} value={form.price} onChange={set("price")} required />
        </label>
        <label className="field">
          <span>Image URL (optional)</span>
          <input className="input" value={form.imageUrl} onChange={set("imageUrl")} placeholder="https://…" />
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={form.active} onChange={set("active")} /> Active (visible in the shop)
        </label>
        <div className="row">
          <button type="submit" className="btn btn-primary" disabled={busy === "form"}>
            {busy === "form" ? "Saving…" : editingId === null ? "Create product" : "Save changes"}
          </button>
          {editingId !== null && (
            <button type="button" className="btn btn-ghost" onClick={resetForm}>Cancel edit</button>
          )}
        </div>
      </form>

      <div className="section">
        <div className="page-header">
          <h2 style={{ margin: 0 }}>Products</h2>
          {products && <span className="muted">{products.length} visible</span>}
        </div>
        <ErrorText>{listError}</ErrorText>
        {!products && !listError && <Loading />}
        {products && products.length === 0 && <EmptyState title="No visible products">Create the first one above.</EmptyState>}
        {products && products.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Category</th>
                  <th className="num">Price</th>
                  <th className="num">Stock</th>
                  <th>Add stock</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {products.map((p) => (
                  <tr key={p.id}>
                    <td className="muted">{p.id}</td>
                    <td>
                      <Link href={`/products/${p.id}`}>{p.name}</Link>
                      {rowMessage[p.id] && (
                        <div className={`sub ${rowMessage[p.id].startsWith("Stock:") ? "hint-ok" : "hint-error"}`}>
                          {rowMessage[p.id]}
                        </div>
                      )}
                    </td>
                    <td>{categoryName(p.categoryId)}</td>
                    <td className="num">{formatVnd(p.price)}</td>
                    <td className="num">{stock[p.id] ?? "…"}</td>
                    <td className="nowrap">
                      <span className="row" style={{ gap: 6, flexWrap: "nowrap" }}>
                        <input
                          type="number"
                          min={1}
                          placeholder="qty"
                          value={stockQty[p.id] ?? ""}
                          onChange={(e) => setStockQty((m) => ({ ...m, [p.id]: e.target.value }))}
                          className="input input-sm input-inline"
                          style={{ width: 76 }}
                        />
                        <button className="btn btn-sm" disabled={busy === p.id} onClick={() => onAddStock(p)}>Add</button>
                      </span>
                    </td>
                    <td className="actions">
                      <button className="btn btn-sm" disabled={busy === p.id} onClick={() => startEdit(p)}>Edit</button>
                      <button className="btn btn-sm btn-danger" disabled={busy === p.id} onClick={() => onHide(p)}>Hide</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
