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

## Image quality
Image Quality applies to new images Agora prepares for model input, including attachments, tool
images, generated images, Phone screenshots, PDF pages and video frames. Choose Light
(1024 px / JPEG 75), Balanced (2048 px / JPEG 80, default), High (4096 px / JPEG 95), or Custom.
Custom supports a longest edge of 128 to 8192 px and JPEG quality from 1 to 100. Settings save
immediately; custom values remain available when switching presets. Images with transparency
use PNG. Existing raster images are never enlarged.

Changing quality affects subsequent processing. Completed attachments, history and queued or
retried requests keep their existing image bytes. PDF and video batches keep the quality selected
when processing starts. Remote-session images preserve their original received bytes.

## Related settings

Context budgeting and Compact are configured separately under [Context](context.md). Automatic titles use [Title Generation](title-generation.md), and image-specific defaults use [Image Generation](image-generation.md).
