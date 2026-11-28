// Compose lays user text out with LineHeightStyle.Default (Trim.Both): the half-leading above the
// first line and below the last line is removed. CSS always keeps that leading, so measure the
// app font once and publish the amount; style-messages.css pulls it back out with negative margins.
let installed = false;

function publish() {
  const probe = document.createElement("span");
  probe.className = "user-text";
  probe.textContent = "M";
  probe.style.cssText = "position:absolute;left:-10000px;top:0;visibility:hidden;white-space:pre;";
  document.body.appendChild(probe);
  const style = getComputedStyle(probe);
  const lineHeight = parseFloat(style.lineHeight);
  const context = document.createElement("canvas").getContext("2d");
  context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
  const metrics = context.measureText("M");
  const contentHeight = (metrics.fontBoundingBoxAscent ?? 0) + (metrics.fontBoundingBoxDescent ?? 0);
  probe.remove();
  if (!(lineHeight > 0) || !(contentHeight > 0) || contentHeight >= lineHeight) return;
  document.documentElement.style.setProperty("--user-text-leading-trim", `${(lineHeight - contentHeight) / 2}px`);
}

export function installTextMetrics() {
  if (installed) return;
  installed = true;
  publish();
  // The app font is served with font-display: swap, so the first measure may see a fallback face.
  document.fonts?.ready.then(publish).catch(() => {});
  document.fonts?.addEventListener?.("loadingdone", publish);
}
