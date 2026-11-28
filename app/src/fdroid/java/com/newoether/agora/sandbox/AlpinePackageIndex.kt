package com.newoether.agora.sandbox

import java.io.File

// ── APKINDEX Parsing ────────────────────────────────

internal data class FullPkgEntry(
    val name: String,
    val version: String,
    val deps: List<String>,
    /** Repository base URL that owns this record, so the archive is fetched from the same index. */
    val repositoryBase: String = "",
)

/** Compare two Alpine-style package versions. Returns >0 if a > b, 0 if equal, <0 if a < b.
 *  Alpine version format: {version}-r{revision}  (e.g. "3.5.2-r1", "1.2.3_pre1-r0").
 *  -r{revision} is the package revision; if omitted, revision=0.
 *  The version part is split into tokens: digit runs vs non-digit runs.
 *  Tokens are compared numerically for digits, lexicographically for letters.
 *  '_' (underscore) acts as a separator with lower priority than '.'. */
internal fun compareAlpineVersions(a: String, b: String): Int {
    fun splitVersion(v: String): Pair<String, Int> {
        val ri = v.lastIndexOf("-r")
        val base = if (ri >= 0) v.substring(0, ri) else v
        val rev  = if (ri >= 0) v.substring(ri + 2).toIntOrNull() ?: 0 else 0
        return base to rev
    }
    fun tokenise(ver: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < ver.length) {
            if (ver[i] == '.' || ver[i] == '_' || ver[i] == '-') {
                tokens.add(ver[i].toString()); i++
            } else if (ver[i].isDigit()) {
                val start = i; while (i < ver.length && ver[i].isDigit()) i++
                tokens.add(ver.substring(start, i))
            } else {
                val start = i; while (i < ver.length && !ver[i].isDigit() && ver[i] != '.' && ver[i] != '_' && ver[i] != '-') i++
                tokens.add(ver.substring(start, i))
            }
        }
        return tokens
    }
    fun tokenWeight(token: String): Int = when {
        token == "~" -> -1
        token.startsWith("alpha") -> -4
        token.startsWith("beta")  -> -3
        token.startsWith("pre")   -> -2
        token.startsWith("rc")    -> -1
        else -> 0
    }
    fun compareToken(ta: String, tb: String): Int? {
        val aDig = ta.toIntOrNull()
        val bDig = tb.toIntOrNull()
        if (aDig != null && bDig != null) return aDig.compareTo(bDig)
        // letter tokens: compare pre-release suffixes first, then lexicographically
        val wa = tokenWeight(ta); val wb = tokenWeight(tb)
        if (wa != 0 || wb != 0) return wa.compareTo(wb)
        return ta.compareTo(tb)
    }

    val (baseA, revA) = splitVersion(a)
    val (baseB, revB) = splitVersion(b)

    val tokensA = tokenise(baseA)
    val tokensB = tokenise(baseB)
    val n = maxOf(tokensA.size, tokensB.size)
    for (idx in 0 until n) {
        val ta = tokensA.getOrElse(idx) { "" }
        val tb = tokensB.getOrElse(idx) { "" }
        if (ta == "_" && tb == "_") continue
        if (ta == "_") return -1   // _ has lower priority than anything except another _
        if (tb == "_") return 1
        if (ta == tb) continue
        val cmp = compareToken(ta, tb) ?: ta.compareTo(tb)
        if (cmp != 0) return cmp
    }
    return revA.compareTo(revB)
}

internal fun parseFullApkIndex(
    indexFile: File,
    preferredPackages: Set<String> = emptySet(),
    repositoryBase: String = "",
): Pair<Map<String, FullPkgEntry>, Map<String, String>> {
    val result = mutableMapOf<String, FullPkgEntry>()
    val provides = mutableMapOf<String, List<String>>()
    val priorities = mutableMapOf<String, Int>()
    val soToPkg = mutableMapOf<String, String>()
    java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.name == "APKINDEX") {
                    val fields = mutableMapOf<Char, String>()
                    fun flushRecord() {
                        val name = fields['P'].orEmpty()
                        val version = fields['V'].orEmpty()
                        val provided = fields['p'].orEmpty()
                        if (name.startsWith("so:") && provided.isNotEmpty()) {
                            soToPkg[name] = provided
                        } else if (name.isNotEmpty() && version.isNotEmpty()) {
                            result[name] = FullPkgEntry(
                                name, version, apkIndexWords(fields['D']), repositoryBase,
                            )
                            provides[name] = apkIndexWords(provided).map { it.substringBefore('=') }
                            priorities[name] = fields['k']?.toIntOrNull() ?: 0
                        }
                        fields.clear()
                    }
                    tar.readBytes().toString(Charsets.UTF_8).lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty()) flushRecord()
                        else if (line.length >= 2 && line[1] == ':') fields[line[0]] = line.substring(2).trim()
                    }
                    flushRecord()
                }
                entry = tar.nextEntry
            }
        }
    }
    for ((name, aliases) in provides) {
        for (alias in aliases) {
            val previous = soToPkg[alias]
            val preferred = name in preferredPackages
            val previousPreferred = previous in preferredPackages
            val priority = priorities[name] ?: 0
            val previousPriority = priorities[previous] ?: 0
            val replace = when {
                previous == null -> true
                preferred != previousPreferred -> preferred
                priority != previousPriority -> priority > previousPriority
                else -> name < previous
            }
            if (replace) soToPkg[alias] = name
        }
    }
    return Pair(result, soToPkg)
}

private fun apkIndexWords(value: String?): List<String> =
    value.orEmpty().split(Regex("\\s+")).filter(String::isNotEmpty)

/** Shared download closure for install and upgrade; apk validates the final transaction. */
internal fun collectAlpinePackageChanges(
    requested: Collection<String>,
    packages: Map<String, FullPkgEntry>,
    providers: Map<String, String>,
    installed: Map<String, String>,
): Set<String> {
    val changes = linkedSetOf<String>()
    val visited = mutableSetOf<String>()
    fun collect(name: String) {
        if (!visited.add(name)) return
        val entry = packages[name] ?: return
        val installedVersion = installed[name]
        if (installedVersion == null || compareAlpineVersions(entry.version, installedVersion) > 0) {
            changes.add(name)
        }
        for (dependency in entry.deps) {
            val dependencyName = dependency.takeWhile { it != '=' && it != '>' && it != '<' && it != '~' }
            if (dependencyName in packages) collect(dependencyName)
            else providers[dependencyName]?.let(::collect)
        }
    }
    requested.forEach(::collect)
    return changes
}
