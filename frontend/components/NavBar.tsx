"use client";

// Dynamic navigation: Login/Register when logged out, email + Logout when logged in,
// "Admin" only for role ADMIN (FR-02, FR-03).

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { logout } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";

const linkStyle = { textDecoration: "none", color: "#1d4ed8" } as const;

export default function NavBar() {
  const router = useRouter();
  const { user, ready } = useCurrentUser();
  const [busy, setBusy] = useState(false);

  async function onLogout() {
    setBusy(true);
    try {
      await logout();
    } finally {
      setBusy(false);
      router.push("/");
      router.refresh();
    }
  }

  return (
    <header
      style={{
        display: "flex",
        flexWrap: "wrap",
        alignItems: "center",
        gap: "8px 16px",
        padding: "12px 16px",
        borderBottom: "1px solid #ddd",
      }}
    >
      <Link href="/" style={{ ...linkStyle, fontWeight: 700, color: "#111" }}>
        ShopFlow
      </Link>
      <Link href="/" style={linkStyle}>Products</Link>
      <Link href="/cart" style={linkStyle}>Cart</Link>
      <Link href="/orders" style={linkStyle}>My orders</Link>
      <Link href="/notifications" style={linkStyle}>Notifications</Link>
      {ready && user?.role === "ADMIN" && (
        <>
          <Link href="/admin/orders" style={linkStyle}>Admin orders</Link>
          <Link href="/admin/products" style={linkStyle}>Admin products</Link>
        </>
      )}
      <span style={{ flex: 1 }} />
      {ready && user ? (
        <span style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 14 }}>
          <span title={`role ${user.role}`}>{user.email || `user #${user.userId}`}</span>
          <button onClick={onLogout} disabled={busy} style={{ cursor: "pointer" }}>
            Logout
          </button>
        </span>
      ) : (
        <span style={{ display: "flex", gap: 12 }}>
          <Link href="/login" style={linkStyle}>Login</Link>
          <Link href="/register" style={linkStyle}>Register</Link>
        </span>
      )}
    </header>
  );
}
