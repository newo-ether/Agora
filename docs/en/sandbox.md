# Local Sandbox

The integrated PRoot environment runs Linux commands on your device. Choose Alpine, Debian, or Ubuntu; each distribution keeps its own Linux system and installed packages.

## Availability

Sandbox support is available in the F-Droid flavor. Google Play builds do not provide local sandbox execution.

## Choose and install a distribution

Open **Settings → Terminal → Manage Local Sandbox**. If the selected system is not installed, open **Environment → Distribution** and choose Alpine, Debian, or Ubuntu. The selection applies immediately and is remembered on this device. Alpine is the default.

Choosing a distribution does not download it. Tap the **Install** row to download and install the selected Linux system. If installation fails, a temporary message explains why; use **Install** again to retry. Once the selected system is ready, the setup rows disappear and the dashboard shows the installed system. To choose a different distribution from an installed system, first reset the current environment; resetting deletes that system and its packages.

## Packages and files

The dashboard shows the selected distribution and version. Install, remove, or upgrade packages through the existing controls. Quick-install suggestions use that distribution’s package names: Alpine uses `apk` and `build-base`, while Debian and Ubuntu use `apt` and `build-essential`. Installed packages stay with their distribution.

All distributions share `/home/agora`, including attachments and your working files. Switching distributions does not copy or remove those files. Programs installed inside a distribution, including virtual environments and compiled binaries, may depend on that distribution and may need to be installed again for another one.

The dashboard shows approximate private file usage for the selected Linux system and the shared home directory, including attachments. Shared storage is excluded. There is no enforced 2 GB sandbox quota; available space depends on the device’s remaining storage.

**Browse Files** opens the sandbox in the system file manager. Optional **Shared Storage** access exposes device shared files at `/mnt/shared`; sandbox commands can read and modify that content once access is granted.

## Reset an environment

**Reset Environment** deletes only the selected distribution’s Linux system and its installed packages. Shared home files and other distributions remain intact. The deleted system cannot be recovered through Undo; use **Install** if you want a fresh system for that distribution.

## Security boundary

PRoot runs a Linux userland; it does not provide a separate kernel or a hardware virtual machine and does not grant Android root. Commands run with the app’s granted access. VPN, private DNS, captive portals, or Android background policy can affect downloads and sandbox networking.

See [Agentic Tools](tools.md) and [Privacy & Security](privacy.md).
