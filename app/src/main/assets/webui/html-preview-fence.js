// Fence admission follows the existing Marked token; partial streaming tails never execute.
export function htmlPreviewFence(token, streaming) {
  const language = (token.lang ?? "").trim().split(/\s+/)[0].toLowerCase();
  if (language !== "html" && language !== "html-preview") return null;
  const opening = token.raw.match(/^ {0,3}(`{3,}|~{3,})[^\r\n]*\r?\n/);
  if (!opening) return null;
  const marker = opening[1][0];
  const closing = new RegExp(`(?:^|\\n) {0,3}${marker}{${opening[1].length},}[ \\t]*(\\r?\\n|$)$`);
  const end = token.raw.slice(opening[0].length).match(closing);
  if (!end || (streaming && !end[1] && !token.previewLineComplete)) return null;
  // Export the original body, including its final line ending, rather than Marked's normalized text.
  const closingStart = opening[0].length + end.index + (end[0].startsWith("\n") ? 1 : 0);
  const source = token.previewFragments?.length === token.raw.length
    ? token.previewFragments.slice(opening[0].length, closingStart).join("")
    : token.raw.slice(opening[0].length, closingStart);
  return { language, source };
}
