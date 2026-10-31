// A path row composes its user or assistant body with the existing branch controls.
import { html } from "./html.js";
import { icon, ICON_CHEVRON_RIGHT } from "./icons.js";
import { t } from "./i18n.js";
import { sync } from "./sync.js";
import { UserBubble } from "./message-user.js";
import { ModelMessage } from "./message-model.js";

/**
 * One path row. `first` and `rows` carry the phone's deletion scope: MessageItem computes
 * deletionRemovesEntireConversation over the visible list, so the first row of a branch deletes the
 * conversation and a compact summary only does so when it is the whole conversation.
 */
export function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming, stopping,
  generating, reduceMotion, first, rows, onOpenSheet, onOpenDetail, search, onPageAction, glyphBirths, conversationId, branchEnabled, onDelete }) {
  const message = body ?? null;
  const branchControl = entry.showBranchSelector && entry.totalBranches > 1 && html`
    <div class="branch-selector">
      <button class="branch-previous" type="button" aria-label=${t.previousBranch} title=${t.previousBranch}
        disabled=${!branchEnabled || entry.branchIndex <= 0} onClick=${() => sync.rowCommand("branch", entry.id, { direction: -1 })}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <span>${entry.branchIndex + 1} / ${entry.totalBranches}</span>
      <button type="button" aria-label=${t.nextBranch} title=${t.nextBranch}
        disabled=${!branchEnabled || entry.branchIndex >= entry.totalBranches - 1} onClick=${() => sync.rowCommand("branch", entry.id, { direction: 1 })}>${icon(ICON_CHEVRON_RIGHT)}</button>
    </div>`;
  let births = glyphBirths.get(entry.id);
  if (!births) { births = new Map(); glyphBirths.set(entry.id, births); }
  const matches = search?.matches.filter(match => match.messageId === entry.id) || [];
  const deletesConversation = !!message && (message.compact ? rows === 1 : first);
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER" && !message.compact
      ? html`<${UserBubble} message=${message} search=${search} matches=${matches}
          conversationId=${conversationId} branchControl=${branchControl}
          reduceMotion=${reduceMotion} generating=${generating}
          deletesConversation=${deletesConversation} onDelete=${() => onDelete(message, deletesConversation)} />`
      : html`<${ModelMessage} message=${message} conversationId=${conversationId} wrap=${wrap} display=${display}
          births=${births} branchControl=${branchControl}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming} stopping=${stopping}
          generating=${generating} reduceMotion=${reduceMotion}
          deletesConversation=${deletesConversation} onDelete=${() => onDelete(message, deletesConversation)}
          search=${search} matches=${matches}
          onFork=${() => onPageAction?.("fork", entry.id)}
          onShare=${() => onPageAction?.("share", entry.id)}
          onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail} />`;
  }
  return html`<div class="message-row" data-id=${entry.id}>${content}</div>`;
}
