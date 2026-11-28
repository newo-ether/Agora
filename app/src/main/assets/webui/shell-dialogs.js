import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog } from "./html.js";
import { CircularProgress } from "./material/progress.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";


/**
 * The drawer overlays the chat with a scrim up to 960 px; wider, it sits beside the chat and the
 * chat narrows, as the app's side-by-side drawer. Both start closed and open from the menu button.
 */
export function ConversationDialog({ state, request, onClose: onClosed }) {
  const dialog = useRef(null);
  const returnFocus = useRef(document.querySelector('.actions-capsule [aria-haspopup="menu"]'));
  const onClose = useDialog(dialog, () => {
    if (sent.current) sync.confirmationExited(sent.current.actionId);
    sync.dismissShare(); onClosed();
  }, returnFocus);
  const sent = useRef(false);
  const busy = !!state.pageAction;
  const sharing = request.type === "share";
  const picking = request.type === "system_prompt";
  const prompt = state.composer?.systemPrompt;
  const [selectedPromptId, setSelectedPromptId] = useState(prompt?.selectedId ?? null);
  useEffect(() => { setSelectedPromptId(prompt?.selectedId ?? null); }, [prompt?.selectedId]);
  const activeTitle = prompt?.items.find(item => item.id === prompt.activeId)?.title || t.noSystemPrompt;
  const heading = picking ? t.systemPrompt : sharing ? t.share
    : request.messageId == null ? t.forkConversationTitle : t.forkFromHere;
  const text = state.shareText;
  function close() { if (!busy && !sent.current) onClose(); }
  useEffect(() => {
    if (sent.current && state.pageResult?.actionId === sent.current.actionId) onClose();
  }, [state.pageResult]);
  async function copy() {
    try { await navigator.clipboard.writeText(text); sync.notify(t.copied); }
    catch (_) { sync.notify(t.copyFailed); }
  }
  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "conversation.md";
    link.click();
    URL.revokeObjectURL(url);
  }
  return html`<dialog ref=${dialog} class=${`attachment-editor page-dialog ${picking ? "prompt-dialog" : ""}`} aria-label=${heading}
    onCancel=${event => { event.preventDefault(); close(); }}
    onClick=${event => { if (event.target === dialog.current) close(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${heading}</h2>
      ${picking ? html`<div class="prompt-options" role="radiogroup" aria-label=${t.systemPrompt}>
        ${[{ id: null, title: t.globalDefault(activeTitle) }, ...(prompt?.items || [])].map(item => html`
          <label class="prompt-option"><span class="prompt-radio"><input type="radio" name="system-prompt" checked=${selectedPromptId === item.id}
            onChange=${() => setSelectedPromptId(item.id)} /></span><span>${item.title}</span></label>`)}
      </div>` : sharing ? (text ? html`<textarea class="share-output" readonly aria-label=${t.share}>${text}</textarea>`
        : html`<${CircularProgress} size=${24} label=${t.loading} />`)
        : html`<p>${t.forkConfirm}</p>`}
      <footer><button type="button" disabled=${busy} onClick=${close}>${sharing ? t.close : t.cancel}</button>
        ${picking ? html`<button type="button" disabled=${state.pendingAction || state.composer?.controls?.lowContextModeEnabled ||
          (selectedPromptId !== null && !prompt?.items.some(item => item.id === selectedPromptId))} onClick=${() => {
            if (sync.editorCommand("system_prompt", { value: selectedPromptId }, request)) onClose();
          }}>${t.save}</button>` : sharing ? text && html`<button type="button" onClick=${copy}>${t.copy}</button><button type="button" onClick=${download}>${t.download}</button>`
          : html`<button type="button" disabled=${busy} onClick=${() => {
            if (sent.current) return;
            sent.current = sync.pageCommand("fork", request,
              request.messageId == null ? {} : { messageId: request.messageId });
            if (!sent.current) onClose();
          }}>${busy ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : t.fork}</button>`}
      </footer>
    </section></dialog>`;
}

/**
 * ChatRenameDialog: the field opens on the stored title and an untouched Save keeps it, so a title
 * the browser never edited is never written back over the stored one.
 */
export function ConversationRenameDialog({ conversation, onClose: onClosed }) {
  const dialog = useRef(null);
  const returnFocus = useRef(conversation.anchor);
  const onClose = useDialog(dialog, onClosed, returnFocus);
  const field = useRef(null);
  const edited = useRef(false);
  useLayoutEffect(() => {
    field.current.value = conversation.title;
    field.current.focus();
  }, []);
  function save() {
    if (edited.current) sync.renameConversation(conversation.conversationId, field.current.value);
    onClose();
  }
  return html`<dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.renameChat}
    onCancel=${event => { event.preventDefault(); onClose(); }}
    onClick=${event => { if (event.target === dialog.current) onClose(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${t.renameChat}</h2>
      <input class="rename-field" type="text" ref=${field} aria-label=${t.renameChat}
        onInput=${() => { edited.current = true; }}
        onKeyDown=${event => { if (event.key === "Enter") { event.preventDefault(); save(); } }} />
      <footer><button type="button" onClick=${onClose}>${t.cancel}</button>
        <button type="button" onClick=${save}>${t.save}</button></footer>
    </section></dialog>`;
}

/**
 * ChatDeleteConfirmDialog: CONFIRM, then PENDING while the phone's owner runs its transaction, then
 * FAILED when it refuses. A pending delete cannot be dismissed, so the dialog cannot outlive it.
 */
export function ConversationDeleteDialog({ pending, failed, complete, actionId, onConfirm, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, () => { sync.confirmationExited(actionId); onClosed(); });
  useEffect(() => { if (complete) onClose(); }, [complete]);
  function close() { if (!pending) onClose(); }
  return html`<dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.deleteChat}
    onCancel=${event => { event.preventDefault(); close(); }}
    onClick=${event => { if (event.target === dialog.current) close(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${t.deleteChat}</h2><p>${t.deleteChatConfirm}</p>
      ${failed && html`<p class="dialog-failure" role="alert">${t.toolStateFailed}</p>`}
      <footer><button type="button" disabled=${pending} onClick=${close}>${t.cancel}</button>
        <button class="destructive" type="button" disabled=${pending} onClick=${onConfirm}>
          ${pending ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : failed ? t.retry : t.delete}
        </button></footer>
    </section></dialog>`;
}
