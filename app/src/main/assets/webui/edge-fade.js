// Horizontal scroll owners fade the edge that still hides content, the way the native heatmap and
// attachment row do. The CSS reads the two numbers written here; 0 means that end is reached.

const TARGETS = ".edge-fade-x, .markdown .katex-display";
const attached = new WeakSet();

const paint = node => {
  const maximum = Math.max(0, node.scrollWidth - node.clientWidth);
  if (maximum <= 1) {
    node.style.setProperty("--edge-fade-left", "0");
    node.style.setProperty("--edge-fade-right", "0");
    return;
  }
  const rtl = getComputedStyle(node).direction === "rtl";
  const left = rtl ? maximum + node.scrollLeft : node.scrollLeft;
  const right = rtl ? -node.scrollLeft : maximum - node.scrollLeft;
  node.style.setProperty("--edge-fade-left", left > 1 ? "1" : "0");
  node.style.setProperty("--edge-fade-right", right > 1 ? "1" : "0");
};

const resize = new ResizeObserver(entries => entries.forEach(entry => paint(entry.target)));

const attach = node => {
  if (attached.has(node)) return;
  attached.add(node);
  node.addEventListener("scroll", () => paint(node), { passive: true });
  resize.observe(node);
  paint(node);
};

const scan = root => {
  if (root.matches?.(TARGETS)) attach(root);
  root.querySelectorAll?.(TARGETS).forEach(attach);
};

export function installEdgeFades() {
  scan(document);
  // The sheet, the composer and every message body re-render, so new scroll owners show up late.
  new MutationObserver(records => {
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (node.nodeType === Node.ELEMENT_NODE) scan(node);
      }
    }
  }).observe(document.body, { childList: true, subtree: true });
  // Late images and fonts change scrollWidth without adding nodes.
  document.addEventListener("load", () => scan(document), true);
}
