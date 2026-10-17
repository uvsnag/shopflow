// Small API client for the gateway. Rough version:
// - access token kept in localStorage (TODO: consider httpOnly cookies)
// - no automatic refresh yet (TODO: refresh on 401 using the refresh token)

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return localStorage.getItem("accessToken");
  } catch {
    return null;
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`API ${res.status}: ${body}`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export async function login(email: string, password: string) {
  const tokens = await api<{ accessToken: string; refreshToken: string }>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  try {
    localStorage.setItem("accessToken", tokens.accessToken);
    localStorage.setItem("refreshToken", tokens.refreshToken);
  } catch {
    // storage unavailable; session will not persist
  }
  return tokens;
}

export interface Product {
  id: number;
  name: string;
  description: string;
  price: number;
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

export const getProducts = (q = "", page = 0) =>
  api<ProductPage>(`/products?q=${encodeURIComponent(q)}&page=${page}&size=20`);

export const placeOrder = () =>
  api("/orders", {
    method: "POST",
    headers: { "Idempotency-Key": crypto.randomUUID() },
  });
