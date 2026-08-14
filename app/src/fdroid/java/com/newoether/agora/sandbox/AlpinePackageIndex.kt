package com.newoether.agora.sandbox

import java.io.File

// ── APKINDEX Parsing ────────────────────────────────

internal data class FullPkgEntry(val name: String, val version: String, val deps: List<String>)

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

internal fun parseFullApkIndex(indexFile: File): Pair<Map<String, FullPkgEntry>, Map<String, String>> {
    val result = mutableMapOf<String, FullPkgEntry>()
    val soToPkg = mutableMapOf<String, String>()
    java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.name == "APKINDEX") {
                    val lines = tar.readBytes().toString(Charsets.UTF_8).lines()
                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        if (!line.startsWith("P:")) continue
                        val name = line.substring(2).trim()
                        var version = ""; var provider = ""
                        val deps = mutableListOf<String>()
                        val isSoEntry = name.startsWith("so:")
                        for (j in i + 1 until minOf(i + 30, lines.size)) {
                            val n = lines[j].trim()
                            if (n.startsWith("C:")) break
                            if (n.startsWith("V:")) version = n.substring(2).trim()
                            if (n.startsWith("p:")) {
                                provider = n.substring(2).trim()
                                if (!isSoEntry) {
                                    // p: lists all provides (so:libfoo.so.1=1.0 so:libbar.so.1=1.0)
                                    for (prov in provider.split(Regex("\\s+"))) {
                                        val pn = prov.takeWhile { it != '=' }
                                        if (pn.isNotEmpty()) {
                                            if (!soToPkg.containsKey(pn) || name == "busybox-binsh" || name == "busybox") {
                                                soToPkg[pn] = name
                                            }
                                        }
                                    }
                                }
                            }
                            if (n.startsWith("D:")) deps.addAll(n.substring(2).trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
                        }
                        if (isSoEntry && provider.isNotEmpty()) soToPkg[name] = provider
                        else if (!isSoEntry && name.isNotEmpty() && version.isNotEmpty()) result[name] = FullPkgEntry(name, version, deps)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }
    return Pair(result, soToPkg)
}
