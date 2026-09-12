# Agora Android App — Build Process Documentation

## Overview

This document describes the complete build process for the Agora Android application, including native binary compilation via `build-proot.sh`, Gradle APK assembly, and the differences between build scripts.

---

## Requirements

### System Requirements

| Component | Version | Purpose |
|-----------|---------|---------|
| **WSL (Windows Subsystem for Linux)** | Arch Linux | Runs `build-proot.sh` for native binary compilation |
| **Android NDK** | r28 (28.2.13676358) | Cross-compiles native code (llama.cpp, proot, talloc) for arm64-v8a |
| **Android SDK** | API 36 (compile/target), minSdk 24 | Builds the Android application |
| **JDK** | 21 | Required by Gradle and Android SDK tools |
| **Gradle** | 9.5.1 (via wrapper) | Build orchestration |
| **Kotlin** | 2.3.21 | Application language |
| **AGP** | 9.2.1 | Android Gradle Plugin |

### WSL Arch Linux Setup

The `build-proot.sh` script **must run inside WSL Arch Linux** (or any Linux with NDK r28). It cannot run natively on Windows.

**One-time WSL setup:**
```powershell
# Install WSL with Arch Linux
wsl --install -d archlinux

# Inside WSL Arch (as root):
useradd -m -G wheel -s /bin/bash newoether
echo 'newoether:newoether' | chpasswd
echo 'newoether ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/newoether

# Install build dependencies
pacman -Sy --noconfirm base-devel clang llvm lld make wget unzip jdk21-openjdk

# Install Android SDK command line tools
mkdir -p /home/newoether/android-sdk/cmdline-tools
cd /home/newoether/android-sdk/cmdline-tools
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip -q cmdline-tools.zip && mv cmdline-tools latest && rm cmdline-tools.zip

# Accept licenses (requires JAVA_HOME)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
yes | /home/newoether/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses

# Install NDK r28 (28.2.13676358)
/home/newoether/android-sdk/cmdline-tools/latest/bin/sdkmanager 'ndk;28.2.13676358'
```

---

## Build Scripts Comparison

### `build-proot.sh` — Native Binary Builder

| Aspect | Details |
|--------|---------|
| **Purpose** | Cross-compiles native binaries (`libproot_exec.so`, `libproot_loader.so`, `libtalloc.so`) for arm64-v8a |
| **Runs In** | WSL Arch Linux (bash) |
| **Input** | Source in `thirdparty/talloc/`, `thirdparty/proot/src/` |
| **Output** | `app/src/main/jniLibs/arm64-v8a/` + `app/src/fdroid/jniLibs/arm64-v8a/` |
| **Incremental** | Yes — uses MD5 hash of sources + NDK path (`.build-proot/build-proot-arm64-v8a/.source_hashes`) |
| **Force Rebuild** | `./build-proot.sh --force` |
| **Key Steps** | 1. Build talloc → sysroot<br>2. Copy proot source, patch for Android, build via GNUmakefile<br>3. Strip binaries to `/tmp/`, copy to jniLibs<br>4. Sync to fdroid flavor |

### `build_fdroid.ps1` — F-Droid Build Script (PowerShell)

| Aspect | Details |
|--------|---------|
| **Purpose** | Full F-Droid build pipeline: native binaries → Gradle assemble |
| **Runs In** | PowerShell (Windows) |
| **Invokes** | `wsl -d archlinux -u newoether -- bash -c "cd /mnt/c/.../Agora && ./build-proot.sh"` then `gradlew.bat assembleFdroidDebug/Release` |
| **Flavor** | `fdroid` (includes PRoot sandbox) |
| **Output** | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` |

### `build.ps1` / `build-googleplay.ps1` — Play Store Build Scripts

| Aspect | Details |
|--------|---------|
| **Purpose** | Play Store build pipeline |
| **Runs In** | PowerShell (Windows) |
| **Flavor** | `play` (no bundled PRoot) |
| **Output** | `app/build/outputs/bundle/play/release/app-play-release.aab` |
| **Difference** | Skips PRoot binary build; uses `bundlePlayRelease` instead of `assembleFdroid*` |

---

## Complete Build Process

### Step 1: Build Native Binaries (if needed)

```powershell
# From PowerShell, runs in WSL
wsl -d archlinux -u newoether -- bash -c "cd /mnt/c/Users/DiegoGzz/Documents/Programas/My-Projects/Agora && ./build-proot.sh"
```

**Or force rebuild:**
```powershell
wsl -d archlinux -u newoether -- bash -c "cd /mnt/c/Users/DiegoGzz/Documents/Programas/My-Projects/Agora && ./build-proot.sh --force"
```

### Step 2: Build APK / AAB

**F-Droid Debug:**
```cmd
gradlew.bat assembleFdroidDebug
```

**F-Droid Release:**
```cmd
gradlew.bat assembleFdroidRelease
```

**Play Store Release (AAB):**
```cmd
gradlew.bat bundlePlayRelease
```

### Step 3: Install on Device

```cmd
adb -s <device-id> install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

---

## Key Differences Summary

| Feature | `build-proot.sh` | `build_fdroid.ps1` | `build.ps1` / `build-googleplay.ps1` |
|---------|------------------|-------------------|--------------------------------------|
| **Language** | Bash | PowerShell | PowerShell |
| **Runs On** | WSL Linux | Windows (calls WSL) | Windows (calls WSL) |
| **Builds Natives** | ✅ Yes | ✅ Yes (via build-proot.sh) | ❌ No (Play flavor has no PRoot) |
| **Gradle Task** | N/A | `assembleFdroid*` | `bundlePlayRelease` |
| **Output** | `.so` files in jniLibs | APK | AAB |
| **Flavor** | N/A (prepares both) | `fdroid` | `play` |
| **Incremental** | ✅ Hash-based | ✅ Gradle incremental | ✅ Gradle incremental |

---

## Build Artifacts

| Artifact | Location | Purpose |
|----------|----------|---------|
| `libproot_exec.so` | `app/src/main/jniLibs/arm64-v8a/` + `app/src/fdroid/jniLibs/arm64-v8a/` | proot PIE executable (sandbox) |
| `libproot_loader.so` | Same | proot static loader |
| `libtalloc.so` | Same | talloc memory allocator (SONAME=libtalloc.so) |
| `app-fdroid-debug.apk` | `app/build/outputs/apk/fdroid/debug/` | Debug APK for F-Droid |
| `app-fdroid-release.apk` | `app/build/outputs/apk/fdroid/release/` | Release APK for F-Droid |
| `app-play-release.aab` | `app/build/outputs/bundle/play/release/` | Play Store bundle |

---

## Common Issues & Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| `env: 'bash\r': No such file` | CRLF line endings | `sed -i 's/\r$//' build-proot.sh` |
| `llvm-strip: Operation not permitted` | Writing stripped binary directly to /mnt/c/ | Strip to `/tmp/` first, then `cp` (fixed in script) |
| `NDK not found` | NDK not installed or wrong path | Install NDK 28.2.13676358 via sdkmanager |
| `readelf not found` | GNUmakefile needs it | Install `binutils` or symlink `llvm-readelf` |
| `signatures do not match` | Different signing key | Uninstall existing app first: `adb uninstall com.newoether.agora` |

---

## Quick Reference Commands

```powershell
# Full F-Droid debug build (native + APK)
wsl -d archlinux -u newoether -- bash -c "cd /mnt/c/Users/DiegoGzz/Documents/Programas/My-Projects/Agora && ./build-proot.sh"
gradlew.bat assembleFdroidDebug
adb -s 88ee3a12 install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk

# Force native rebuild
wsl -d archlinux -u newoether -- bash -c "cd /mnt/c/Users/DiegoGzz/Documents/Programas/My-Projects/Agora && ./build-proot.sh --force"

# Clean build
gradlew.bat clean
gradlew.bat assembleFdroidDebug

# Run tests
gradlew.bat test

# Verify Kotlin source size (999-line limit)
gradlew.bat verifyKotlinFileSize
```

---

## File Reference for AGENTS.md

Add the following entry to `AGENTS.md` under the "Key Files for Onboarding" or "Development Conventions" section:

```markdown
| `BUILD_PROCESS.md` | Complete build documentation: requirements, build-proot.sh vs other scripts, step-by-step process, troubleshooting |
```

Or add a dedicated section:

```markdown
## Build Process Documentation

See `BUILD_PROCESS.md` for:
- Complete system requirements (WSL, NDK, SDK, JDK)
- Detailed comparison of `build-proot.sh`, `build_fdroid.ps1`, `build.ps1`
- Step-by-step build and install commands
- Common issues and fixes
- Quick reference commands
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-09-06 | Initial documentation based on v2.1.0 build process |