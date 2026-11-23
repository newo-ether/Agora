// #224: a file dropped anywhere in the chat window attaches through the paperclip's ingress instead
// of letting Firefox/Chrome navigate to it. Text drags are left to the browser.

/** Returns whether the drop carries files; a text/plain drag keeps the browser's own behaviour. */
export function dropCarriesFiles(types) {
  return Array.from(types ?? []).includes("Files");
}

/**
 * Routes one dropped payload. Returns true when the drop belonged to attachments, so the caller can
 * cancel the browser navigation even while no conversation is selected.
 */
export function routeFileDrop({ types, files, sandboxAttachments, target, retain, upload }) {
  if (!dropCarriesFiles(types)) return false;
  const dropped = Array.from(files ?? []);
  if (dropped.length && target) {
    if (sandboxAttachments) retain(dropped, target);
    else upload(dropped, null, target);
  }
  return true;
}

/**
 * Installs the document-wide listeners. Getters are read at drop time, so the routing always uses
 * the conversation and mode the page shows now.
 */
export function installFileDrop({ document, getSandboxAttachments, attachmentTarget, retain, upload }) {
  const onDragOver = (event) => {
    if (dropCarriesFiles(event.dataTransfer?.types)) event.preventDefault();
  };
  const onDrop = (event) => {
    const routed = routeFileDrop({
      types: event.dataTransfer?.types,
      files: event.dataTransfer?.files,
      sandboxAttachments: getSandboxAttachments(),
      target: attachmentTarget(),
      retain,
      upload,
    });
    if (routed) event.preventDefault();
  };
  document.addEventListener("dragover", onDragOver);
  document.addEventListener("drop", onDrop);
  return () => {
    document.removeEventListener("dragover", onDragOver);
    document.removeEventListener("drop", onDrop);
  };
}
