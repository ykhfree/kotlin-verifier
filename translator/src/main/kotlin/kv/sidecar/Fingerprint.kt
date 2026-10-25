package kv.sidecar

import java.security.MessageDigest

/** [text] with every whitespace run as one space and the ends trimmed (CRLF counts as one run). */
fun normalizeWhitespace(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

/**
 * The fingerprint of a reviewed text (DESIGN-v0.md 8.3 "reviewed 지문", DESIGN-B.md 9.3 UB3, milestone B6): CRLF
 * becomes LF, every whitespace run becomes one space, the ends are trimmed, and the fingerprint is the first 16 hex
 * characters of the SHA-256 of that text (UTF-8). A `reviewed` note records it as `sha256 "<hex>"`. A snapshot block
 * (DESIGN-C.md 5.2 rule 3) has the fingerprint of its text by the same rule.
 */
fun reviewFingerprint(text: String): String {
    val normal = normalizeWhitespace(text)
    return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
}
