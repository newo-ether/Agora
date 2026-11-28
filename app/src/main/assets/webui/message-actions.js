// Message-row actions and dialogs share the existing sync command and modal owners.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html, useDialog } from "./html.js";
import { CircularProgress } from "./material/progress.js";
import {
  icon, ICON_CALL_SPLIT, ICON_CONTENT_COPY, ICON_DELETE, ICON_INFO, ICON_MORE_VERT,
  ICON_REFRESH, ICON_SHARE, ICON_LINK,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";

/** Copy and Select Text retain the original body, including a compact Subagent receipt's result. */
export function readableText(message) {
  const source = message?.source;
  if (source?.kind !== "ask_user") return message?.text?.markdown ?? "";
  return source.askUser
    .map((item) => `${item.question}\n${item.answer ?? t.sourceUnanswered}`)
    .join("\n\n");
}

/** The phone answers a copy with a haptic; the nearest browser answer is the app's own notice. */
export async function copyRowText(message) {
  try {
    await navigator.clipboard.writeText(message.presentation?.copyText ?? readableText(message));
    sync.notify(t.copied);
  } catch (_) {
    sync.notify(t.copyFailed);
  }
}

/** MessageInfoDialog keeps the device calendar in a fixed yyyy-MM-dd HH:mm:ss pattern. */
function formatRowTime(value) {
  const date = new Date(value);
  const pad = (part) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** One IconButton of a message row: 32 dp, tinted at 60 % of onSurfaceVariant and 30 % when off. */
export function RowAction({ path, size, enabled = true, label, onClick }) {
  return html`
    <button class="row-action" type="button" disabled=${!enabled} title=${label} aria-label=${label}
      style=${{ "--row-icon": `${size}px` }} onClick=${onClick}>${icon(path)}</button>`;
}

/** One AgoraDropdownMenuItem; [destructive] paints it with the phone's error colour. */
export function RowMenuItem({ path, label, enabled = true, destructive = false, onClick }) {
  return html`
    <button class=${destructive ? "dropdown-item destructive" : "dropdown-item"} role="menuitem"
      type="button" disabled=${!enabled} onClick=${onClick}>${icon(path)}<span>${label}</span></button>`;
}

/**
 * The 32 dp overflow button of a row and the AgoraDropdownMenu it anchors. [items] draws the items
 * with the close action; the menu hands focus back to the button when it was holding it.
 */
export function RowMore({ reduceMotion, items, enabled = true }) {
  const anchor = useRef(null);
  const [open, setOpen] = useState(false);
  const [retained, setRetained] = useState(false);
  const close = () => setOpen(false);
  useEffect(() => { if (!enabled) setOpen(false); }, [enabled]);
  return html`
    <button class="row-action" type="button" ref=${anchor} title=${t.options} aria-label=${t.options} disabled=${!enabled}
      aria-haspopup="menu" aria-expanded=${open ? "true" : "false"}
      onClick=${() => { setRetained(true); setOpen(!open); }}>${icon(ICON_MORE_VERT)}</button>
    ${(open || retained) && html`<${MoreMenu} expanded=${open && enabled} reduceMotion=${reduceMotion} anchor=${anchor}
      onClose=${close}
      onExited=${(ownedFocus) => { setRetained(false); if (ownedFocus) anchor.current?.focus(); }}>
      ${items(close)}
    </${MoreMenu}>`}`;
}

/** MessageInfoDialog: the phone's own figures, and an em dash where its provider reported none. */
export function MessageInfoDialog({ message, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const usage = message.usage;
  const lines = [t.timeWithLabel(formatRowTime(message.timestamp))];
  if (message.participant !== "USER") {
    lines.push(t.modelWithLabel(message.modelDisplay || message.modelName || t.unknown));
    lines.push(t.inputWithLabel(usage?.input == null ? t.noFigure
      : usage.cachedInput == null ? t.tokenCount(usage.input)
        : t.tokenCountWithCached(usage.input, usage.cachedInput)));
    lines.push(t.outputWithLabel(usage?.output == null ? t.noFigure : t.tokenCount(usage.output)));
    if (usage?.tokensPerSecond != null) {
      lines.push(t.speedWithLabel(`~${usage.tokensPerSecond.toFixed(1)} token/s`));
    }
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.messageInfo}
      onCancel=${(event) => { event.preventDefault(); onClose(); }}
      onClick=${(event) => { if (event.target === dialog.current) onClose(); }}
      onKeyDown=${(event) => event.stopPropagation()}>
      <section><h2>${t.messageInfo}</h2>
        <ul class="message-info-list">
          ${lines.map((line, index) => html`<li key=${index}>${line}</li>`)}
        </ul>
        <footer><button type="button" onClick=${onClose}>${t.close}</button></footer>
      </section></dialog>`;
}

/**
 * The phone's inline editor for a user row: it resends the edited text through the phone's own edit
 * owner, so only Save commits. Escape and the scrim dismiss without sending anything.
 */
export function MessageEditDialog({ message, canEdit, onClose: onClosed }) {
  const dialog = useRef(null);
  const onClose = useDialog(dialog, onClosed);
  const field = useRef(null);
  const [value, setValue] = useState(readableText(message));
  useLayoutEffect(() => { field.current?.focus(); }, []);
  function save() {
    if (!canEdit() || message.source || !value.trim()) return;
    if (sync.rowCommand("edit", message.id, { text: value })) onClose();
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${t.edit}
      onCancel=${(event) => { event.preventDefault(); onClose(); }}
      onClick=${(event) => { if (event.target === dialog.current) onClose(); }}>
      <section><h2>${t.edit}</h2>
        <textarea ref=${field} class="message-edit" rows="6" value=${value} aria-label=${t.edit}
          onInput=${(event) => setValue(event.currentTarget.value)}
          onKeyDown=${(event) => {
            if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
              event.preventDefault();
              save();
              return;
            }
            event.stopPropagation();
          }} />
        <footer><button type="button" onClick=${onClose}>${t.cancel}</button>
          <button type="button" disabled=${!value.trim()} onClick=${save}>${t.save}</button></footer>
      </section></dialog>`;
}

/**
 * The phone's own question for a destructive row action. The conversation variant belongs to the
 * phone's conversation owner, so the browser asks it whenever its own branch says the cascade would
 * take every visible row with it, which never under-warns.
 */
export function MessageDeleteDialog({ state, request, onClose: onClosed }) {
  const title = request.compactOnly ? t.deleteCompactTitle
    : request.deletesConversation ? t.deleteConversationTitle : t.deleteMessageTitle;
  const body = request.compactOnly ? t.deleteCompactConfirm
    : request.deletesConversation ? t.deleteConversationFromMessageConfirm : t.deleteMessageConfirm;
  const dialog = useRef(null);
  const sent = useRef(null);
  const [failed, setFailed] = useState(false);
  const pending = !!sent.current && !failed;
  const onClose = useDialog(dialog, () => {
    if (sent.current) sync.confirmationExited(sent.current.actionId);
    onClosed();
  });
  useEffect(() => {
    const result = state.switching?.result;
    if (!sent.current || result?.actionId !== sent.current.actionId) return;
    if (result.success) onClose();
    else setFailed(true);
  }, [state.switching?.result]);
  function close() { if (!pending) onClose(); }
  function confirm() {
    if (pending) return;
    const command = sync.rowCommand("delete", request.messageId, { confirmation: true });
    if (command) { sent.current = command; setFailed(false); }
    else setFailed(true);
  }
  return html`
    <dialog ref=${dialog} class="attachment-editor page-dialog" aria-label=${title}
      onCancel=${event => { event.preventDefault(); close(); }}
      onClick=${event => { if (event.target === dialog.current) close(); }}
      onKeyDown=${event => event.stopPropagation()}>
      <section><h2>${title}</h2><p>${body}</p>
        ${failed && html`<p class="dialog-failure" role="alert">${t.toolStateFailed}</p>`}
        <footer><button type="button" disabled=${pending} onClick=${close}>${t.cancel}</button>
          <button class="destructive" type="button" disabled=${pending} onClick=${confirm}>
            ${pending ? html`<${CircularProgress} size=${20} stroke=${3} label=${t.loading} />` : failed ? t.retry : t.delete}
          </button></footer>
      </section></dialog>`;
}

/**
 * AssistantMessageContent's action line: a 44 dp Box under the answer with 12 dp of top padding.
 * Copy and MoreVert are the information group, Regenerate, Fork and Share the terminal group, and
 * both ride one alpha: 320 ms in, 220 ms out, linear.
 */
export function AssistantActions({ message, visible, generating, reduceMotion, onFork, onShare, onInfo, onDelete, branchControl, onSources }) {
  const terminalEnabled = visible && !generating;
  const copyTextValue = message.presentation?.copyText ?? readableText(message);
  const [retainSources, setRetainSources] = useState(visible);
  useLayoutEffect(() => { if (visible) setRetainSources(true); }, [visible]);
  const sources = message.presentation?.sources ?? [];
  return html`
    <div class="message-information" data-visible=${visible} inert=${!visible} aria-hidden=${visible ? null : "true"}
      onTransitionEnd=${event => {
        if (event.target === event.currentTarget && event.propertyName === "opacity" && !visible) setRetainSources(false);
      }}>
      ${(visible || retainSources) && sources.length > 0 && html`<button class="citation-summary" type="button" disabled=${!visible}
        onClick=${onSources}>${icon(ICON_LINK)}<span>${message.presentation.sourcesTitle}</span></button>`}
      <div class="message-actions">
      ${copyTextValue.trim().length > 0 && html`<${RowAction} path=${ICON_CONTENT_COPY} size=${16}
        label=${t.copy} enabled=${visible} onClick=${() => copyRowText(message)} />`}
      <${RowAction} path=${ICON_REFRESH} size=${19} enabled=${terminalEnabled} label=${t.regenerate}
        onClick=${() => sync.rowCommand("regenerate", message.id)} />
      <${RowAction} path=${ICON_CALL_SPLIT} size=${18} enabled=${terminalEnabled} label=${t.forkFromHere}
        onClick=${onFork} />
      <${RowAction} path=${ICON_SHARE} size=${16} enabled=${terminalEnabled} label=${t.share}
        onClick=${onShare} />
      <${RowMore} reduceMotion=${reduceMotion} enabled=${visible} items=${(close) => html`
        <${RowMenuItem} path=${ICON_INFO} label=${t.info} enabled=${visible} onClick=${() => { close(); onInfo(); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${terminalEnabled}
          onClick=${() => { close(); onDelete(); }} />`} />
      ${branchControl}
      </div>
    </div>`;
}
