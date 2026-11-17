import type { NextConfig } from "next";

// The browser only ever talks to this Next.js server; /api/* is forwarded to the Spring Boot
// backend (same origin for the browser, so no CORS needed in this setup).
// API_URL is read when the app is built/started: http://localhost:8080 locally, http://app:8080 in Docker.
const apiUrl = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  experimental: {
    // AI answers can take a while (an agent turn may make several Gemini calls); default is 30 s.
    proxyTimeout: 120_000,
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/api/:path*` }];
  },
};

export default nextConfig;
