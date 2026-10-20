# Generation

Open **Settings → Generation** to configure default request parameters. Conversation-specific overrides may replace these values.

## Common parameters

- Temperature controls randomness.
- Top P limits cumulative probability mass.
- Top K limits candidate tokens where supported.
- Maximum output tokens caps the generated response.
- Frequency and presence penalties discourage repetition where supported.

Agora sends only parameters supported by the selected provider protocol.

## Thinking

Enable a thinking budget and choose either a token budget or a supported reasoning level: **Minimal**, **Low**, **Medium**, **High**, **xHigh**, or **Max**. Provider and model support varies; unsupported controls may be ignored or translated by that provider adapter.

## OpenAI-compatible service tier

For compatible endpoints, the service tier can be **Off**, **Auto**, **Default**, **Flex**, or **Fast**. Availability, latency, and billing are controlled by the provider.

## Related settings

Context budgeting and Compact are configured separately under [Context](context.md). Automatic titles use [Title Generation](title-generation.md), and image-specific defaults use [Image Generation](image-generation.md).

## Interactive answers

Optional calculators, charts and diagrams are available through
[Interactive answers](interactive-answers.md). Enable them under Settings → Interaction.
