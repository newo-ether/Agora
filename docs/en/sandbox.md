# Local Sandbox

The integrated PRoot environment runs Linux commands on your device. Choose Alpine, Debian, or Ubuntu; each distribution keeps its own Linux system and installed packages.

## Availability

Sandbox support is available in the F-Droid flavor. Google Play builds do not provide local sandbox execution.

## Choose and install a distribution

Open **Settings → Terminal → Manage Local Sandbox**. In **Environment**, open **Distribution** and choose Alpine, Debian, or Ubuntu. The selection applies immediately and is remembered on this device. Existing installations continue using Alpine until you choose another distribution.

Selecting an uninstalled distribution does not download it. Use **Install** on its dashboard to download and install its Linux system. You can switch back to an installed distribution without reinstalling it. Only one distribution is active at a time; stop running sandbox jobs and wait for installation or package operations to finish before switching.

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
