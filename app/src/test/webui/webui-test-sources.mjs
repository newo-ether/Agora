import fs from "node:fs";

const root = new URL("../../main/assets/webui/", import.meta.url);

// Follow the same local stylesheet imports as the browser, preserving their cascade order.
export function readWebUiStyles(url = new URL("style.css", root)) {
  const css = fs.readFileSync(url, "utf8").replaceAll("\r\n", "\n");
  return css.replace(/^@import url\(["']([^"']+)["']\);$/gm,
    (_, path) => readWebUiStyles(new URL(path, url)));
}
