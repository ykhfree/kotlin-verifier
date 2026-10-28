package kv.spec

// The local secret check of `kv spec snapshot` (DESIGN-C.md 6.3 rule 2, 10.8): spec text goes to an LLM, so a block
// that holds something shaped like a key, a token or a password is replaced before it is written.

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
        // A named credential with a value of 8 or more characters that holds a digit: `password: hunter22`. The digit
        // keeps code such as `token = nextToken()` in KDoc out.
        "assigned-credential" to
            Regex("""(?i)\b(?:password|passwd|pwd|secret|api[_ -]?key|access[_ -]?token|auth[_ -]?token|token)\b\s*[:=]\s*["']?(?=[^\s"'`<>]*\d)[^\s"'`<>]{8,}"""),
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
