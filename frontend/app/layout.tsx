import type { Metadata } from "next";
import NavBar from "@/components/NavBar";

export const metadata: Metadata = {
  title: "ShopFlow",
  description: "Small shop built on microservices",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body style={{ fontFamily: "system-ui, sans-serif", margin: 0, color: "#111" }}>
        <NavBar />
        <main style={{ padding: 16, maxWidth: 960, margin: "0 auto" }}>{children}</main>
      </body>
    </html>
  );
}
