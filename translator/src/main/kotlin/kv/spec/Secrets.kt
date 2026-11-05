package kv.spec

// The local gate of `kv spec snapshot` (DESIGN-C.md 6.3 rule 2, 10.8; DESIGN-J.md 6.1, 8.2): spec text goes to an LLM,
// and quotes of it go into sidecars. So a block that holds something shaped like a key, a token or a password is
// replaced before it is written, and an email or a mobile number in a block is replaced by a constant.

/** Each kind of secret and the pattern of its shape, in the order they are tried. */
private val SECRET_SHAPES =
    listOf(
        "private-key" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        "aws-access-key" to Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        "atlassian-api-token" to Regex("""\bATATT[A-Za-z0-9_\-=]{20,}"""),
        "github-token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}|\bgithub_pat_[A-Za-z0-9_]{22,}"""),
        "slack-token" to Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
        "anthropic-key" to Regex("""\bsk-ant-[A-Za-z0-9_\-]{20,}"""),
        "openai-key" to Regex("""\bsk-[A-Za-z0-9_\-]{20,}"""),
        "jwt" to Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""),
        // Shapes of pasted logs and commands (DESIGN-J.md 8.2). As for a named credential below, the value must hold a
        // digit, so that prose (`bearer authentication/authorization`) and `-u 1000:1000` or `--user www-data:www-data`
        // stay.
        "auth-header" to Regex("""(?i)\bauthorization["']?\s*[:=]\s*["']?(?:bearer|basic|token)\s+(?=[A-Za-z0-9._~+/=-]*\d)[A-Za-z0-9._~+/=-]{8,}"""),
        "bearer-token" to Regex("""(?i)\bbearer\s+(?=[A-Za-z0-9._~+/=-]*\d)[A-Za-z0-9._~+/=-]{16,}"""),
        "cli-credential" to Regex("""(?:^|\s)(?:-u|--user)\s+["']?[^\s:"']+:(?=[^\s"']*\d)[^\s"']{8,}"""),
        "url-credential" to Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s/:@"']+:[^\s/@"']{3,}@"""),
        "cookie" to Regex("""(?i)\b(?:set-)?cookie\s*:\s*[^\s=;]+=[^\s;]{8,}"""),
        // A named credential with a value of 8 or more characters that holds a digit: `password: hunter22`. The digit
        // keeps code such as `token = nextToken()` in KDoc out. The name may have a prefix that ends in `_` or `-`
        // (`DB_PASSWORD`, `client_secret`) and a closing quote (`"password": "..."`). A code name such as `nextPageToken`
        // has no such prefix, so it stays.
        "assigned-credential" to
            Regex("""(?i)(?<![A-Za-z0-9])(?:[A-Za-z0-9_-]*[_-])?(?:password|passwd|pwd|secret|api[_ -]?key|access[_ -]?(?:key|token)|auth[_ -]?token|token)["']?\s*[:=]\s*["']?(?=[^\s"'`<>]*\d)[^\s"'`<>]{8,}"""),
        // The same with a Korean name (DESIGN-C.md 10.13). Korean joins words (`관리자비밀번호`), so no left boundary.
        "assigned-credential" to Regex("""(?i)$KOREAN_CREDENTIAL\s*[:=：]\s*["']?(?=[^\s"'`<>]*\d)[^\s"'`<>]{8,}"""),
        // After a particle (`비밀번호는 ...`) only an ASCII value with a letter and a digit counts, so that a rule such as
        // `비밀번호는 최소 8자 이상` stays.
        "assigned-credential" to Regex("""(?i)$KOREAN_CREDENTIAL\s*[은는이가을를]\s+(?=[!-~]*[A-Za-z])(?=[!-~]*\d)[!-~]{8,}"""),
    )

/** Korean names of a credential, for the `assigned-credential` shapes. */
private const val KOREAN_CREDENTIAL = """(?:비밀번호|비번|암호|패스워드|시크릿|비밀\s?키|(?:액세스|인증)\s?토큰|토큰|api\s?키)"""

/** The kind of the first secret shape in [text], or null. */
fun secretKind(text: String): String? = SECRET_SHAPES.firstOrNull { (_, shape) -> shape.containsMatchIn(text) }?.first

/** The text that replaces a block that holds a secret of [kind]. The original text is written nowhere. */
fun redacted(kind: String) = "[redacted by kv spec snapshot: $kind]"

// An address must not go on with `:` or `/`: `git@github.com:org/repo.git` and `https://user@example.com/path` hold none.
private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9:/-])""")

private val MOBILE = Regex("""(?<!\d)01[016789][- .]?\d{3,4}[- .]?\d{4}(?!\d)""")

/** The text of a block after the gate, and what the gate did. [note] is null if it did nothing, and never holds a value. */
internal data class Gated(
    val text: String,
    val note: String?,
)

/**
 * [text] after the gate. The secret check comes first: a mail address inside `mongodb://app:hunter22x@db.example.com` would
 * otherwise lose the `@` that the URL shape needs (DESIGN-J.md 6.1 rule 3). The gate of its own result does nothing.
 */
internal fun gated(text: String): Gated {
    secretKind(text)?.let { return Gated(redacted(it), "redacted, it holds a $it (DESIGN-C.md 6.3)") }
    var count = 0
    val replaced =
        MOBILE.replace(EMAIL.replace(text) { count++; "[email]" }) {
            count++
            "[phone]"
        }
    return Gated(replaced, if (count > 0) "replaced $count email or phone shape(s) (DESIGN-J.md 6.1)" else null)
}
