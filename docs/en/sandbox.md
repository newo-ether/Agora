# Alpine Sandbox

The integrated PRoot Alpine Linux environment provides an isolated user-space command environment for agentic code execution.

## Availability

Sandbox support is available in the F-Droid flavor. Google Play builds use an unavailable/no-op implementation and do not expose equivalent execution.

## Manage the environment

Under **Settings → Sandbox**, you can enable the feature, install or upgrade the root filesystem, inspect packages, browse files, and reset the environment. Package installation and commands use the sandbox's own filesystem and network access.

The dashboard shows approximate private file usage for the root filesystem and home directory,
including attachments. Shared storage is excluded. There is no enforced 2 GB sandbox quota; usage
is limited by the device's available storage.

A shared-storage mount allows selected Android files to be visible inside the sandbox. Grant only the access you need: sandbox commands can modify mounted content.

## Security boundary

PRoot is process/filesystem isolation, not a hardware virtual machine. It does not grant Android root, but commands run with the app's granted access. VPN, private DNS, captive portals, or Android background policy can affect downloads and sandbox networking.

See [Agentic Tools](tools.md) and [Privacy & Security](privacy.md).
