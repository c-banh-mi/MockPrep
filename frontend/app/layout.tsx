import type { Metadata } from "next";
import "bootstrap/dist/css/bootstrap.min.css";
import "./globals.css";
import NavBar from "@/components/NavBar";
import { AuthProvider } from "@/lib/auth";

export const metadata: Metadata = {
  title: "MockPrep",
  description: "Book mock interviews and resume reviews with experienced engineers, with an AI booking assistant.",
  icons: { icon: "/icon.svg" },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <NavBar />
          <main className="container">{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
