// SPDX-License-Identifier: GPL-3.0-or-later
import type { NextConfig } from "next";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH || undefined;

/** Static export: the dashboard is plain files, hostable on Firebase Hosting (Spark) or GitHub Pages. */
const config: NextConfig = {
  output: "export",
  trailingSlash: true,
  images: { unoptimized: true },
  basePath,
  reactStrictMode: true,
  poweredByHeader: false,
};

export default config;
