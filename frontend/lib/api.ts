// API client for the gateway (NEXT_PUBLIC_API_URL, default http://localhost:8080/api/v1).
// - access/refresh tokens kept in localStorage (all access is try/catch guarded, SSR safe)
// - a 401 triggers one POST /auth/refresh, stores the new pair and retries the request once
// - errors arrive as RFC 7807 ProblemDetail and are surfaced as ApiError { status, detail }

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";
const ACCESS_KEY = "accessToken";
const REFRESH_KEY = "refreshToken";

/** Fired on window whenever the stored tokens change (login, refresh, logout). */
export const AUTH_EVENT = "shopflow:auth";

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: string,
    public readonly title?: string,
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

/** Human readable message for any thrown value (ApiError.detail when present). */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.detail;
  if (e instanceof Error) return e.message;
  return String(e);
}

// ---------------------------------------------------------------------------
// Token storage (SSR safe)
// ---------------------------------------------------------------------------

function storageGet(key: string): string | null {
  if (typeof window === "undefined") return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(key, value);
  } catch {
    // storage unavailable; session will not persist
  }
}

function storageRemove(key: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

function notifyAuthChange() {
  if (typeof window === "undefined") return;
  try {
    window.dispatchEvent(new Event(AUTH_EVENT));
  } catch {
    // ignore
  }
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export function getToken(): string | null {
  return storageGet(ACCESS_KEY);
}

export function getRefreshToken(): string | null {
  return storageGet(REFRESH_KEY);
}

export function setTokens(tokens: TokenPair) {
  storageSet(ACCESS_KEY, tokens.accessToken);
  storageSet(REFRESH_KEY, tokens.refreshToken);
  notifyAuthChange();
}

export function clearTokens() {
  storageRemove(ACCESS_KEY);
  storageRemove(REFRESH_KEY);
  notifyAuthChange();
}

// ---------------------------------------------------------------------------
// Current user (decoded from the JWT payload; never verified client-side)
// ---------------------------------------------------------------------------

export type Role = "CUSTOMER" | "ADMIN";

export interface CurrentUser {
  userId: string;
  role: Role;
  email: string;
  /** expiry as epoch seconds */
  exp: number;
}

function decodeBase64Url(input: string): string {
  const b64 = input.replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split(".");
    if (parts.length < 2) return null;
    return JSON.parse(decodeBase64Url(parts[1]));
  } catch {
    return null;
  }
}

/**
 * The logged-in user from the stored access token, or null.
 * An expired access token still counts as logged in when a refresh token exists
 * (the next request will refresh it); otherwise null.
 */
export function getCurrentUser(): CurrentUser | null {
  const token = getToken();
  if (!token) return null;
  const payload = decodeJwtPayload(token);
  if (!payload || typeof payload.sub !== "string") return null;
  const exp = typeof payload.exp === "number" ? payload.exp : 0;
  if (exp && exp * 1000 < Date.now() && !getRefreshToken()) return null;
  const role: Role = payload.role === "ADMIN" ? "ADMIN" : "CUSTOMER";
  return {
    userId: payload.sub,
    role,
    email: typeof payload.email === "string" ? payload.email : "",
    exp,
  };
}

// ---------------------------------------------------------------------------
// Core request with refresh-on-401
// ---------------------------------------------------------------------------

async function rawFetch(path: string, options: RequestInit, token: string | null): Promise<Response> {
  try {
    return await fetch(`${BASE_URL}${path}`, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.headers as Record<string, string> | undefined),
      },
    });
  } catch {
    throw new ApiError(0, `Cannot reach the API at ${BASE_URL}. Is the gateway running?`);
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  let detail = `Request failed with status ${res.status}`;
  let title: string | undefined;
  try {
    const text = await res.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { detail?: string; title?: string; message?: string };
        title = body.title;
        detail = body.detail || body.message || body.title || detail;
      } catch {
        detail = text;
      }
    }
  } catch {
    // keep the default message
  }
  if (res.status === 429 && detail.startsWith("Request failed")) {
    detail = "Too many attempts. Please wait a minute and try again.";
  }
  return new ApiError(res.status, detail, title);
}

let refreshInFlight: Promise<string | null> | null = null;

/** Calls /auth/refresh once (shared between concurrent 401s); clears tokens on failure. */
async function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const refreshToken = getRefreshToken();
      if (!refreshToken) return null;
      try {
        const res = await fetch(`${BASE_URL}/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
        });
        if (!res.ok) {
          clearTokens();
          return null;
        }
        const pair = (await res.json()) as TokenPair;
        setTokens(pair); // the old refresh token is revoked server-side: always keep the new pair
        return pair.accessToken;
      } catch {
        clearTokens();
        return null;
      }
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  let res = await rawFetch(path, options, getToken());
  if (res.status === 401 && !path.startsWith("/auth/") && getRefreshToken()) {
    const fresh = await refreshAccessToken();
    if (fresh) res = await rawFetch(path, options, fresh);
  }
  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const json = (body: unknown): RequestInit => ({ method: "POST", body: JSON.stringify(body) });

// ---------------------------------------------------------------------------
// Auth (FR-01, FR-02, FR-03)
// ---------------------------------------------------------------------------

export async function register(email: string, password: string, fullName: string) {
  return api<{ id: number; email: string }>("/auth/register", json({ email, password, fullName }));
}

export async function login(email: string, password: string) {
  const tokens = await api<TokenPair>("/auth/login", json({ email, password }));
  setTokens(tokens);
  return tokens;
}

export async function logout() {
  const refreshToken = getRefreshToken();
  try {
    if (refreshToken) await api<void>("/auth/logout", json({ refreshToken }));
  } catch {
    // the server may already have revoked it; clear locally anyway
  } finally {
    clearTokens();
  }
}

// ---------------------------------------------------------------------------
// Products and inventory (FR-04, FR-05)
// ---------------------------------------------------------------------------

export interface Category {
  id: number;
  name: string;
}

/** Seeded categories (the API has no category endpoint). */
export const CATEGORIES: Category[] = [
  { id: 1, name: "Electronics" },
  { id: 2, name: "Books" },
  { id: 3, name: "Clothing" },
];

export const categoryName = (id: number) => CATEGORIES.find((c) => c.id === id)?.name ?? `Category #${id}`;

export interface Product {
  id: number;
  categoryId: number;
  name: string;
  description: string;
  price: number; // integer VND
  imageUrl: string | null;
  active: boolean;
}

export interface ProductPage {
  items: Product[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export const formatVnd = (amount: number, currency = "VND") => `${amount.toLocaleString()} ${currency}`;

export function getProducts(q = "", page = 0, category: number | "" = "", size = 20) {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) });
  if (category !== "") params.set("category", String(category));
  return api<ProductPage>(`/products?${params.toString()}`);
}

export const getProduct = (id: number | string) => api<Product>(`/products/${id}`);

export interface Stock {
  productId: number;
  available: number;
}

/** Stock left for a product; resolves to 0 when the inventory row does not exist (404). */
export async function getStock(productId: number | string): Promise<Stock> {
  try {
    return await api<Stock>(`/inventory/${productId}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return { productId: Number(productId), available: 0 };
    throw e;
  }
}

// ---------------------------------------------------------------------------
// Cart (FR-06)
// ---------------------------------------------------------------------------

export interface CartItem {
  productId: number;
  quantity: number;
}

export interface Cart {
  items: CartItem[];
  updatedAt: string | null;
}

export const getCart = () => api<Cart>("/cart");

/** Sets the absolute quantity; 0 removes the line. */
export const setCartItem = (productId: number, quantity: number) =>
  api<Cart>(`/cart/items/${productId}`, { method: "PUT", body: JSON.stringify({ quantity }) });

export const removeCartItem = (productId: number) => api<Cart>(`/cart/items/${productId}`, { method: "DELETE" });

/** Adds `quantity` to whatever is already in the cart for that product. */
export async function addToCart(productId: number, quantity = 1): Promise<Cart> {
  const cart = await getCart();
  const current = cart.items.find((i) => i.productId === productId)?.quantity ?? 0;
  return setCartItem(productId, current + quantity);
}

// ---------------------------------------------------------------------------
// Orders (FR-07, FR-09, FR-10)
// ---------------------------------------------------------------------------

export type OrderStatus =
  | "PENDING"
  | "STOCK_RESERVED"
  | "PAID"
  | "SHIPPED"
  | "DELIVERED"
  | "CANCELLED"
  | "REFUNDED";

export const ORDER_STATUSES: OrderStatus[] = [
  "PENDING",
  "STOCK_RESERVED",
  "PAID",
  "SHIPPED",
  "DELIVERED",
  "CANCELLED",
  "REFUNDED",
];

/** Statuses in which a customer may still cancel (PAID -> REFUNDED). */
export const CANCELLABLE_STATUSES: OrderStatus[] = ["PENDING", "STOCK_RESERVED", "PAID"];

/** Statuses after which nothing changes without an admin action. */
export const SETTLED_STATUSES: OrderStatus[] = ["PAID", "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED"];

export interface OrderItem {
  productId: number;
  productName: string;
  unitPrice: number;
  quantity: number;
}

export interface Order {
  id: number;
  status: OrderStatus;
  totalAmount: number;
  currency: string;
  createdAt: string;
  items: OrderItem[];
}

const idempotent = (): Record<string, string> => ({ "Idempotency-Key": crypto.randomUUID() });

// NFR-10: idempotent on the Idempotency-Key header (reused if the request is retried after a refresh).
export const placeOrder = () => api<Order>("/orders", { method: "POST", headers: idempotent() });

export const getOrders = (page = 0, size = 20) => api<Order[]>(`/orders?page=${page}&size=${size}`);

export const getOrder = (id: number | string) => api<Order>(`/orders/${id}`);

export const cancelOrder = (id: number | string) => api<Order>(`/orders/${id}/cancel`, { method: "POST" });

// ---------------------------------------------------------------------------
// Payments (FR-08)
// ---------------------------------------------------------------------------

export interface PaymentResult {
  paymentId: number;
  orderId: number;
  status: string;
}

/** Starts a payment; the amount is looked up server-side. Settles asynchronously (poll the order). */
export const payOrder = (orderId: number) =>
  api<PaymentResult>("/payments", {
    method: "POST",
    headers: idempotent(),
    body: JSON.stringify({ orderId }),
  });

// ---------------------------------------------------------------------------
// Notifications (FR-11)
// ---------------------------------------------------------------------------

export interface Notification {
  id: number;
  type: string;
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}

export const getNotifications = (page = 0, size = 50) =>
  api<Notification[]>(`/notifications?page=${page}&size=${size}`);

// ---------------------------------------------------------------------------
// Admin (FR-12 .. FR-16; the gateway answers 403 for non-admins)
// ---------------------------------------------------------------------------

export interface ProductInput {
  categoryId: number;
  name: string;
  description: string;
  price: number;
  imageUrl: string | null;
  active: boolean;
}

export interface AdminStock {
  productId: number;
  available: number;
  reserved: number;
}

export function adminGetOrders(status: OrderStatus | "" = "", page = 0, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set("status", status);
  return api<Order[]>(`/admin/orders?${params.toString()}`);
}

export const adminShipOrder = (id: number) => api<Order>(`/admin/orders/${id}/ship`, { method: "POST" });

export const adminDeliverOrder = (id: number) => api<Order>(`/admin/orders/${id}/deliver`, { method: "POST" });

export const adminCreateProduct = (input: ProductInput) => api<Product>("/admin/products", json(input));

export const adminUpdateProduct = (id: number, input: ProductInput) =>
  api<Product>(`/admin/products/${id}`, { method: "PUT", body: JSON.stringify(input) });

export const adminDeleteProduct = (id: number) => api<void>(`/admin/products/${id}`, { method: "DELETE" });

export const adminAddStock = (productId: number, quantity: number) =>
  api<AdminStock>(`/admin/inventory/${productId}/add`, json({ quantity }));

export const adminRefundPayment = (paymentId: number) =>
  api<{ paymentId: number; status: string }>(`/admin/payments/${paymentId}/refund`, { method: "POST" });
