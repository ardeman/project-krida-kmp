import { defineConfig } from "astro/config";

// Static site for GitHub Pages. Output: plain HTML in web/dist, zero JavaScript.
//
// - Custom domain (krida.ardeman.com): leave BASE_PATH empty. public/CNAME sets the domain.
// - No custom domain (https://<user>.github.io/<repo>): build with BASE_PATH=/<repo>.
const base = process.env.BASE_PATH || "/";

export default defineConfig({
  site: "https://krida.ardeman.com",
  base,
  output: "static",
  trailingSlash: "ignore",
  compressHTML: true,
  build: {
    // CSS goes inline in <head>: one request for the whole page, no render-blocking stylesheet.
    inlineStylesheets: "always",
    format: "directory",
  },
});
