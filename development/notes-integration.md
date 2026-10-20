# Notes sharing boundary

Owner decision, 18 October 2026: Notes owns its own processing. Agora has no dedicated
Kalamos Notes integration. Notes sends text, images and files through Android ACTION_SEND /
ACTION_SEND_MULTIPLE, using the same importer, destination picker and draft adoption as other apps.
Receiving a share never sends a message or starts AI processing automatically.

There are no Notes attachment-menu launchers, editor/result handoffs, document inbox, proposal or
handwriting tools, automatic returns, integration settings, or Notes background workers/receivers.
KOReader excerpt metadata and generic Android sharing remain supported independently.

Existing chat records and attachments remain intact. Nullable legacy attachment metadata and narrow
read-side cover/archive handling preserve previously saved bytes; they confer no tool capability or
authority to launch, process or mutate a notebook. Old Notes settings are ignored on archive import.
No migration deletes user files, conversations or historical tool results.
