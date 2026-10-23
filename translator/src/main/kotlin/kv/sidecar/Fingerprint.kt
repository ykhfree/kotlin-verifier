package kv.sidecar

import java.security.MessageDigest

/**
 * The fingerprint of a reviewed text (DESIGN-v0.md 8.3 "reviewed 지문", DESIGN-B.md 9.3 UB3, milestone B6): CRLF
 * becomes LF, every whitespace run becomes one space, the ends are trimmed, and the fingerprint is the first 16 hex
 * characters of the SHA-256 of that text (UTF-8). A `reviewed` note records it as `sha256 "<hex>"`.
 */
fun reviewFingerprint(text: String): String {
    val normal = text.replace("\r\n", "\n").replace(Regex("""\s+"""), " ").trim()
    return MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
}
