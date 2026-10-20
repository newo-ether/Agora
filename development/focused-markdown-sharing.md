# Focused Markdown sharing — 19 October 2026

Owner approved an additional answer action while retaining the existing Share icon unchanged.
The existing icon still shares its generation transcript through Android. The additional Checklist
icon is labelled **Choose content** and uses the same answer source as Copy (including citations).
It adds no Notes-specific destination, tool, settings, automatic return or background processing.

The preview is black on white with Previous/Next navigation. Full-width blocks wrap to their
measured content height, capped at half the available page height. Text exceeding that cap ends
with an ellipsis; Edit text and the shared payload retain the full source. Each page fits as many
complete blocks as its available height allows.
The position label shows the visible block range and total, and Previous returns to the prior
page start without resetting selections or edits. All
blocks are selected initially; the common case is deselecting the opening preface and closing
commentary. A heading offers Remove section/Keep section through the next peer/ancestor heading.
A block can be edited in the shared copy without changing the original answer. The original GFM
source offsets preserve code fences, lists, tables, escaping, links and line endings. Top-level
list items are separately selectable; code and tables remain one block. Long block previews are
truncated visually and Edit text exposes the complete source. No content is silently truncated in
the shared payload. All/None, page and block edits survive ordinary recreation through saved UI
state; parsing is demand-driven on a background dispatcher. Android sharing is launched only after
Share Markdown is explicitly pressed. Its existing inline/file fallback remains the only outgoing
transport. Closing/cancelling the preview changes no conversation or draft.

Notes in Kalamos-final independently registers a generic text/Markdown SEND receiver. It copies
incoming text/granted files, saves the current note through its ordinary close path, and uses the
existing Markdown importer. Reverse sharing continues through Share Markdown → Agora → chat picker.
Standard shares create independent copies; they do not replace old chat responses or maintain a
live link. User performs physical Android/e-ink acceptance.

Verification evidence is kept in Kalamos-final/artifacts/agora-markdown-selection-2026-10-19.

## Compact preview sizing follow-up

Cards use their actual measured height instead of dividing each page into two weighted slots.
The body preview receives at most half the available page height, with space reserved for its
editing/section controls; longer text ellipsizes visually. Short cards remain shorter than that
cap. The viewport measures only the current page plus the first non-fitting candidate. Next
starts at that candidate, and Previous restores the prior start. The position label reports the
visible block range. Full source ranges and outgoing Markdown are unchanged.

Native graphics checks verify more than two short cards fit, selection survives paging, a long
preview is ellipsized, and the complete long paragraph is shared. Full F-Droid suite: 3316 tests,
zero failures/errors, five existing skips. Play focused parser/dialog suite: 8 tests, zero failures.
Kotlin source-size validation passes. Physical Android/e-ink acceptance remains user-owned.
