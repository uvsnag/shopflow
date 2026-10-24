import type { Metadata } from "next";
import NavBar from "@/components/NavBar";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "ShopFlow", template: "%s · ShopFlow" },
  description: "Small shop built on microservices",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <NavBar />
        <main className="site-main">
          <div className="container">{children}</div>
        </main>
        <footer className="site-footer">
          <div className="container">
            <span>ShopFlow · order and payment microservices demo</span>
            <span>Spring Boot · Kafka · PostgreSQL · Next.js</span>
          </div>
        </footer>
      </body>
    </html>
  );
}
