// User-message bodies preserve raw text, source labels, attachment occurrences and action state.
import { useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { highlightSearch } from "./markdown.js";
import {
  icon, ICON_ACCOUNT_TREE, ICON_QUESTION_ANSWER, ICON_REPEAT, ICON_SCHEDULE,
  ICON_EDIT, ICON_MORE_VERT, ICON_IMAGE, ICON_CONTENT_COPY, ICON_SELECT_ALL, ICON_INFO, ICON_DELETE,
} from "./icons.js";
import { MoreMenu } from "./menu.js";
import { t } from "./i18n.js";
import { DetailSheet } from "./detail-sheet.js";
import { AttachmentViewer } from "./attachments.js";
import {
  readableText, copyRowText, RowAction, RowMenuItem, MessageInfoDialog, MessageEditDialog,
} from "./message-actions.js";

const SOURCE_LABELS = {
  task: [ICON_SCHEDULE, "sourceTask"],
  loop: [ICON_REPEAT, "sourceLoop"],
  subagent: [ICON_ACCOUNT_TREE, "sourceSubagent"],
  ask_user: [ICON_QUESTION_ANSWER, "sourceAskUser"],
};

/** MessageSourceLabel: a 14 dp icon and labelSmall sit 4 dp above an automatic user bubble. */
function SourceLabel({ source }) {
  const labelled = SOURCE_LABELS[source?.kind];
  if (!labelled) return null;
  return html`
    <div class="message-source">${icon(labelled[0])}<span>${t[labelled[1]]}</span></div>`;
}

/** Completion display comes from the model; child instructions have no result display metadata. */
function SubagentResult({ result }) {
  const name = result.name.trim() || t.sourceSubagent;
  const status = t.subagentStatuses[result.status];
  return html`<div class="user-text subagent-result" role="group" aria-label=${t.sourceSubagent}>
    <div class="subagent-result-name">${name}</div>
    ${status && html`<div class="subagent-result-status">${status}</div>`}
  </div>`;
}

/**
 * AskUserAnswerBlocks: each question at 14/20 and 60 %, its answer at the user body, and one blank
 * line between pairs. Nobody answering leaves the localized unanswered label at 60 %.
 */
function fillAskUserPairs(node, items) {
  node.replaceChildren();
  // Separate blocks, as on the phone: an inline 14/20 question inside the 24.2 px body would sit on
  // the body's line strut and keep the body's line spacing. style.css adds the blank line.
  let sourceStart = 0;
  items.forEach((item) => {
    const question = document.createElement("div");
    question.className = "ask-user-question";
    question.dataset.sourceStart = String(sourceStart);
    question.textContent = item.question;
    sourceStart += item.question.length + 1;
    const answer = document.createElement("div");
    answer.className = item.answer == null ? "ask-user-answer unanswered" : "ask-user-answer";
    answer.dataset.sourceStart = String(sourceStart);
    answer.textContent = item.answer ?? t.sourceUnanswered;
    sourceStart += answer.textContent.length + 2;
    node.append(question, answer);
  });
}

/** UserMessageBubble: text in a primaryContainer bubble, long press (or right click) for its menu. */
export function UserBubble({ message, search, matches, reduceMotion, generating, deletesConversation, conversationId, branchControl, onDelete }) {
  const text = useRef(null);
  const menuButton = useRef(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(false);
  const [info, setInfo] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [editing, setEditing] = useState(false);
  const editable = !generating && message.source == null;
  const editAllowed = useRef(editable);
  editAllowed.current = editable;
  const canEdit = () => editAllowed.current;
  const edit = () => { if (canEdit()) setEditing(true); };
  useLayoutEffect(() => { if (!editable) setEditing(false); }, [editable]);
  const [preview, setPreview] = useState(null);
  const [failedImages, setFailedImages] = useState(new Set());
  const attachments = message.attachments ?? [];
  const media = attachments.filter(item => !item.unavailable && item.type !== "file").map(item => ({
    ...item, name: item.fileName, kind: "source",
    url: `/api/message-attachments/${encodeURIComponent(conversationId)}/${encodeURIComponent(message.id)}/${item.index}`,
  }));
  const askUser = message.source?.kind === "ask_user" ? message.source.askUser : null;
  const result = message.source?.kind === "subagent" ? message.source.subagentResult : null;
  const visible = readableText(message);
  const hasText = !result && (message.text?.markdown ?? "").length > 0;
  const hasBody = hasText || !!askUser || !!result;
  const askUserKey = JSON.stringify(askUser);
  const searchKey = JSON.stringify([!!search?.query.trim(), matches.map(match =>
    [match.key, match.start, match.endExclusive])]);
  const activeMatch = search?.matches[search.index]?.key;
  useLayoutEffect(() => {
    if (!text.current) return;
    if (askUser) fillAskUserPairs(text.current, askUser);
    else text.current.textContent = visible;
  }, [visible, askUserKey, hasText]);
  useLayoutEffect(() => {
    return text.current ? highlightSearch(text.current, search, matches) : undefined;
  }, [visible, askUserKey, hasText, searchKey]);
  useLayoutEffect(() => {
    const active = CSS.highlights.get("agora-search-active");
    for (const range of CSS.highlights.get("agora-search") ?? []) {
      if (!text.current?.contains(range.startContainer)) continue;
      if (range.searchKey === activeMatch) active?.add(range);
      else active?.delete(range);
    }
  }, [visible, askUserKey, hasText, searchKey, activeMatch]);
  return html`
    <div class="user-row" onContextMenu=${(event) => {
      event.preventDefault();
      setRetainedMenu(true);
      setMenuOpen(true);
    }}>
      <${SourceLabel} source=${message.source} />
      <div class="user-message-content">
        <div class="user-hover-actions">
          <${RowAction} path=${ICON_EDIT} size=${18} label=${t.edit} enabled=${editable}
            onClick=${edit} />
          <button class="row-action" type="button" ref=${menuButton} title=${t.options} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen} onClick=${() => { setRetainedMenu(true); setMenuOpen(!menuOpen); }}>
            ${icon(ICON_MORE_VERT)}</button>
        </div>
        <div class=${`user-bubble${result ? " subagent-result-bubble" : ""}`}>
          ${attachments.length > 0 && html`<div class="user-attachments" data-body-gap=${hasBody}>
            ${attachments.map(item => {
              const index = media.findIndex(current => current.index === item.index);
              const failed = item.unavailable || failedImages.has(item.index);
              // Captioned media keeps its filename outside the thumbnail's indication and hit box.
              const captioned = item.type === "file" || item.type === "pdf" || item.unavailable;
              const thumbnail = html`<button key=${item.index} class=${`user-attachment${captioned ? " captioned-thumbnail" : ""}`} type="button" title=${item.fileName}
                aria-label=${item.fileName || t.attachments} disabled=${failed || index < 0}
                onClick=${() => setPreview(index)}>
                ${!failed && index >= 0 ? html`<img src=${media[index].url} alt=${item.fileName || t.attachments}
                  onError=${() => setFailedImages(current => new Set([...current, item.index]))} />`
                  : html`<span class="attachment-missing">${icon(ICON_IMAGE)}</span>`}
              </button>`;
              return captioned ? html`<span key=${item.index} class="user-attachment-column">
                ${thumbnail}<span class="user-attachment-name">${item.fileName}</span>
              </span>` : html`<span key=${item.index} class="user-attachment-media">${thumbnail}</span>`;
            })}
          </div>`}
          ${result ? html`<${SubagentResult} result=${result} />`
            : (hasText || askUser) && html`<div class="user-text" ref=${text}></div>`}
        </div>
      </div>
      ${branchControl}
      ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
        anchor=${menuButton} onClose=${() => setMenuOpen(false)}
        onExited=${(ownedFocus) => {
          setRetainedMenu(false);
          if (ownedFocus) menuButton.current?.focus();
        }}>
        <${RowMenuItem} path=${ICON_CONTENT_COPY} label=${t.copy} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); copyRowText(message); }} />
        <${RowMenuItem} path=${ICON_EDIT} label=${t.edit} enabled=${editable}
          onClick=${() => { setMenuOpen(false); edit(); }} />
        <${RowMenuItem} path=${ICON_SELECT_ALL} label=${t.selectText} enabled=${visible.trim().length > 0}
          onClick=${() => { setMenuOpen(false); setSelecting(true); }} />
        <${RowMenuItem} path=${ICON_INFO} label=${t.info}
          onClick=${() => { setMenuOpen(false); setInfo(true); }} />
        <${RowMenuItem} path=${ICON_DELETE} label=${t.delete} destructive enabled=${!generating}
          onClick=${() => { setMenuOpen(false); onDelete(); }} />
      </${MoreMenu}>`}
      ${selecting && !retainedMenu && html`<${DetailSheet} title=${t.selectText}
        display=${{ reduceMotion }} focusReturn=${menuButton} onClose=${() => setSelecting(false)}>
        <div class="detail-sheet-select-text">${visible}</div>
      </${DetailSheet}>`}
      ${info && html`<${MessageInfoDialog} message=${message} onClose=${() => setInfo(false)} />`}
      ${preview != null && html`<${AttachmentViewer} viewer=${{ items: media, index: preview }}
        onNavigate=${setPreview} onClose=${() => setPreview(null)} />`}
      ${editing && editable && html`<${MessageEditDialog} message=${message} canEdit=${canEdit} onClose=${() => setEditing(false)} />`}
    </div>`;
}
