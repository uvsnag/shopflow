import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "ShopFlow",
  description: "Small shop built on microservices",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body style={{ fontFamily: "system-ui, sans-serif", margin: 0 }}>
        <header style={{ display: "flex", gap: 16, padding: "12px 16px", borderBottom: "1px solid #ddd" }}>
          <Link href="/">ShopFlow</Link>
          <Link href="/cart">Cart</Link>
          <Link href="/orders">My orders</Link>
          <Link href="/login">Login</Link>
        </header>
        <main style={{ padding: 16, maxWidth: 960, margin: "0 auto" }}>{children}</main>
      </body>
    </html>
  );
}
