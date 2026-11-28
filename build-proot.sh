#!/usr/bin/env bash
set -euo pipefail
# build-proot.sh - Build proot native binaries for the Agora Android app.
# Invoked from build.ps1 / build-googleplay.ps1 / build_fdroid.ps1.
# Must run inside WSL Arch (or any Linux with NDK 28.2.13676358).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORCE="${1:-}"

# ── NDK auto-detection ────────────────────────────────────────
# Priority: ANDROID_NDK_HOME env var, then well-known paths
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -d "/home/newoether/android-sdk/ndk/28.2.13676358" ]; then
    NDK="/home/newoether/android-sdk/ndk/28.2.13676358"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    # F-Droid buildserver: pick the latest installed NDK
    NDK=$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)
    NDK="${NDK%/}"
else
    echo "ERROR: NDK not found. Set ANDROID_NDK_HOME or install NDK 28.2.13676358."
    exit 1
fi

# Toolchain: NDK r28 on Linux uses llvm/prebuilt/linux-x86_64
TC_PREFIX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CROSS_PREFIX="aarch64-linux-android26"
CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
STRIP="${TC_PREFIX}/llvm-strip"
export PATH="${TC_PREFIX}:$PATH"

echo "=== build-proot.sh: NDK=$NDK ==="

# ── Paths ──────────────────────────────────────────────────────
TALLOC_SRC="$SCRIPT_DIR/thirdparty/talloc"
PROOT_SRC="$SCRIPT_DIR/thirdparty/proot/src"
BLD="$SCRIPT_DIR/.build-proot"
SYSROOT="$BLD/sysroot/arm64-v8a"
SYSROOT_LIB="$SYSROOT/lib"
SYSROOT_INC="$SYSROOT/include"
BLD_DIR="$BLD/build-proot-arm64-v8a"
LOADER_OUT="$BLD_DIR/loader-out"
JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
FDROID_JNILIBS="$SCRIPT_DIR/app/src/fdroid/jniLibs/arm64-v8a"

# ── Ensure readelf is available (GNUmakefile needs it) ─────────
if ! command -v readelf &>/dev/null; then
    if command -v llvm-readelf &>/dev/null; then
        READELF_DIR="$BLD/.tmp-bin"
        mkdir -p "$READELF_DIR"
        ln -sf "$(command -v llvm-readelf)" "$READELF_DIR/readelf"
        export PATH="$READELF_DIR:$PATH"
    fi
fi

# ── Hash-based incremental detection ───────────────────────────
HASH_FILE="$BLD_DIR/.source_hashes"
need_rebuild=false

calc_hashes() {
    local hash=""
    # Hash talloc source files
    hash+=$(md5sum "$TALLOC_SRC/talloc.c" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/talloc.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/config.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/replace.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    # Hash proot Makefile
    hash+=$(md5sum "$PROOT_SRC/GNUmakefile" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    # Hash NDK path (rebuild if NDK changes)
    hash+=$(echo "$NDK" | md5sum | cut -d' ' -f1)
    echo "$hash"
}

if [ "$FORCE" = "--force" ]; then
    need_rebuild=true
    echo "  (forced rebuild)"
elif [ ! -f "$HASH_FILE" ]; then
    need_rebuild=true
    echo "  (first build, no hash file)"
elif [ ! -f "$JNILIBS/libproot_exec.so" ] || \
     [ ! -f "$JNILIBS/libproot_loader.so" ] || \
     [ ! -f "$JNILIBS/libtalloc.so" ]; then
    need_rebuild=true
    echo "  (missing jniLibs output)"
else
    new_hash=$(calc_hashes)
    old_hash=$(cat "$HASH_FILE")
    if [ "$new_hash" != "$old_hash" ]; then
        need_rebuild=true
        echo "  (source changed)"
    else
        echo "  (up to date, skipping)"
    fi
fi

if [ "$need_rebuild" = false ]; then
    echo "=== build-proot.sh: All binaries up to date ==="
    exit 0
fi

echo "=== build-proot.sh: Building proot binaries ==="

# ── Create directories ─────────────────────────────────────────
mkdir -p "$SYSROOT_LIB" "$SYSROOT_INC" "$LOADER_OUT" \
         "$JNILIBS" "$FDROID_JNILIBS" "$BLD_DIR/loader"

# ── Step 1: Build talloc ───────────────────────────────────────
echo "  [1/4] Building libtalloc.so..."
# Build from source directory so __FILE__ expands to relative path (e.g.
# "talloc.c:N") on both local and CI — avoids embedding the absolute build path.
(
    cd "$TALLOC_SRC"
    "$CC" -fPIC -O2 -shared \
        -Wl,-soname,libtalloc.so \
        -o "$SYSROOT_LIB/libtalloc.so" \
        talloc.c \
        -DHAVE_CONFIG_H \
        -I.
)
cp "$TALLOC_SRC/talloc.h" "$SYSROOT_INC/"
echo "  [1/4] Done: $(stat -c%s "$SYSROOT_LIB/libtalloc.so") bytes"

# ── Step 2: Build proot (in-tree inside the build dir) ─────────
# The proot GNUmakefile only builds correctly in-tree: its compile
# rule uses "$(SRC)$<", and an out-of-tree `make -f` invocation makes
# VPATH resolve $< to an absolute path, so $(SRC) gets prepended twice
# (e.g. .../proot/src//.../proot/src/cli/cli.c). Copy the sources into
# the build dir and build there so SRC stays relative.
echo "  [2/4] Building proot (GNUmakefile)..."
PROOT_BLD="$BLD_DIR/src"
rm -rf "$PROOT_BLD"
mkdir -p "$PROOT_BLD"
cp -r "$PROOT_SRC/." "$PROOT_BLD/"
# Drop any stale build artifacts that may have been copied from the
# source tree (keeps the tracked .check_*.c probe sources intact).
find "$PROOT_BLD" -name '*.o' -delete
find "$PROOT_BLD" -name '*.d' -delete
find "$PROOT_BLD" -name '*.res' -delete
rm -f "$PROOT_BLD/build.h" "$PROOT_BLD/proot" "$PROOT_BLD/loader/loader" \
      "$PROOT_BLD/.check_process_vm" "$PROOT_BLD/.check_seccomp_filter"
# ── Cross-compilation source fixes ─────────────────────────────
# The feature-detection probes are meant to compile/run on the build
# host; when cross-compiling for Android that is meaningless, so stub
# them to force the (Android-supported) features on. Also add a
# <string.h> include that newer clang (NDK r28) rejects as an implicit
# function declaration error (strcmp/memset).
printf 'int main(void){return 0;}\n' > "$PROOT_BLD/.check_process_vm.c"
printf 'int main(void){return 0;}\n' > "$PROOT_BLD/.check_seccomp_filter.c"
ASHMEM_C="$PROOT_BLD/extension/ashmem_memfd/ashmem_memfd.c"
if [ -f "$ASHMEM_C" ] && ! grep -q '#include <string.h>' "$ASHMEM_C"; then
    sed -i '1i #include <string.h>' "$ASHMEM_C"
fi
# ── Patch loader-info.awk for POSIX awk (mawk) compatibility ───
# F-Droid buildserver uses mawk; gawk-only strtonum and \y are not available.
cat > "$PROOT_BLD/loader/loader-info.awk" <<'ENDAWK'
function hextonum(hex,   i, n, c, idx) {
    hex = tolower(hex)
    n = 0
    for (i = 1; i <= length(hex); i++) {
        c = substr(hex, i, 1)
        idx = index("0123456789abcdef", c)
        if (idx == 0) break
        n = n * 16 + (idx - 1)
    }
    return n
}
$NF == "pokedata_workaround" { pokedata_workaround = hextonum($2) }
$NF == "_start" { start = hextonum($2) }
END {
    print "#include <unistd.h>"
    print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround - start) ";"
}
ENDAWK
(
    cd "$PROOT_BLD"
    export SOURCE_DATE_EPOCH=0
    export CPPFLAGS="-I${SYSROOT_INC} -DSYS_SECCOMP=1"
    export LDFLAGS="-L${SYSROOT_LIB}"
    export CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
    # GIT=/bin/true: suppress git-describe so VERSION is not set in build.h;
    # both local and CI then fall back to proot.h's "5.1.0" — identical output.
    # PROOT_UNBUNDLE_LOADER="loader-out": fixed relative string instead of the
    # absolute build-dir path, so the embedded loader-path string is the same
    # on every machine. (ProotSandboxManager always overrides via PROOT_LOADER.)
    make CROSS_COMPILE="${CROSS_PREFIX}-" \
        PROOT_UNBUNDLE_LOADER="loader-out" \
        GIT=/bin/true \
        proot
)
echo "  [2/4] Done: $(stat -c%s "$PROOT_BLD/proot") bytes"

# ── Step 3: Strip and deploy binaries to jniLibs ───────────────
echo "  [3/4] Stripping and deploying..."

# proot PIE executable -> libproot_exec.so
"$STRIP" --strip-all \
    "$BLD_DIR/src/proot" \
    -o "$JNILIBS/libproot_exec.so"

# Loader (static ELF, already stripped by make) -> libproot_loader.so
LOADER_SRC="$BLD_DIR/src/loader/loader"
if [ ! -f "$LOADER_SRC" ]; then
    echo "ERROR: loader binary not found at $LOADER_SRC"
    exit 1
fi
cp "$LOADER_SRC" "$JNILIBS/libproot_loader.so"

# talloc -> jniLibs (strip)
"$STRIP" --strip-all \
    "$SYSROOT_LIB/libtalloc.so" \
    -o "$JNILIBS/libtalloc.so"

echo "  [3/4] Binaries deployed:"
echo "    $(stat -c%s "$JNILIBS/libproot_exec.so") bytes  libproot_exec.so"
echo "    $(stat -c%s "$JNILIBS/libproot_loader.so") bytes  libproot_loader.so"
echo "    $(stat -c%s "$JNILIBS/libtalloc.so") bytes  libtalloc.so"

# ── Step 4: Sync to fdroid jniLibs flavor ──────────────────────
echo "  [4/4] Syncing to fdroid jniLibs..."
cp "$JNILIBS/libproot_exec.so" "$FDROID_JNILIBS/libproot_exec.so"
cp "$JNILIBS/libproot_loader.so" "$FDROID_JNILIBS/libproot_loader.so"
cp "$JNILIBS/libtalloc.so" "$FDROID_JNILIBS/libtalloc.so"

# ── Save hash for next incremental check ───────────────────────
calc_hashes > "$HASH_FILE"

echo "=== build-proot.sh: Complete ==="
