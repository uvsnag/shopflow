"use client";

// Dynamic navigation: Login/Register when logged out, email + Logout when logged in,
// "Admin" only for role ADMIN (FR-02, FR-03). The current section is highlighted.

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { logout } from "@/lib/api";
import { useCurrentUser } from "@/lib/useCurrentUser";

function NavLink({
  href,
  children,
  admin,
  exact,
}: {
  href: string;
  children: React.ReactNode;
  admin?: boolean;
  exact?: boolean;
}) {
  const pathname = usePathname();
  const active = exact ? pathname === href : pathname === href || pathname.startsWith(href + "/");
  const className = ["nav-link", active ? "active" : "", admin ? "admin" : ""].filter(Boolean).join(" ");
  return (
    <Link href={href} className={className} aria-current={active ? "page" : undefined}>
      {children}
    </Link>
  );
}

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

  const label = user ? user.email || `user #${user.userId}` : "";

  return (
    <header className="site-header">
      <div className="container">
        <Link href="/" className="brand">
          <span className="brand-mark" aria-hidden="true">S</span>
          ShopFlow
        </Link>
        <NavLink href="/" exact>Products</NavLink>
        <NavLink href="/cart">Cart</NavLink>
        <NavLink href="/orders">My orders</NavLink>
        <NavLink href="/notifications">Notifications</NavLink>
        {ready && user?.role === "ADMIN" && (
          <>
            <NavLink href="/admin/orders" admin>Admin orders</NavLink>
            <NavLink href="/admin/products" admin>Admin products</NavLink>
          </>
        )}
        <span className="spacer" />
        {ready && user && (
          <span className="nav-user">
            <span className="avatar" aria-hidden="true">{label.slice(0, 1)}</span>
            <span className="email" title={`role ${user.role}`}>{label}</span>
            <button className="btn btn-sm" onClick={onLogout} disabled={busy}>
              {busy ? "Logging out…" : "Logout"}
            </button>
          </span>
        )}
        {ready && !user && (
          <span className="row">
            <NavLink href="/login">Login</NavLink>
            <Link href="/register" className="btn btn-primary btn-sm">Register</Link>
          </span>
        )}
      </div>
    </header>
  );
}
