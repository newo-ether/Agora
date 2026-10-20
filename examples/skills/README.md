# Composed Skills using Alpine reference files

Create two Skills in Settings using the content of these examples. Name them
`clinical-source-reader.md` and `clinical-osce.md`. Use a concise description for each and optionally
assign `clinical-osce` as the examiner shortcut. Set the source reader's path in its instructions
to your actual directory. Enable/setup the existing Alpine terminal integration.

Use `/skills` to select the examiner, add a condition/question count, and Send. It invokes the
source reader by filename in the ordinary tool loop, then conducts the exercise in chat. No source
corpus is bundled. References are literal: update the instructions after a rename. The sample
shortcut avoids replacing an existing `/osce` template. Clinical/model quality requires separate
assessment; these examples demonstrate composition and selected-source loading.
