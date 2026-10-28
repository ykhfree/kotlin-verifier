package kv.scripts

import com.sun.net.httpserver.HttpServer
import kv.emit.python
import kv.json.jsonOf
import kv.spec.SnapshotInput
import kv.spec.writeSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C6 acceptance 3 (DESIGN-C.md 10.8, test 2): scripts/confluence_fetch.py end to end, against a fake Confluence Cloud
 * (a JDK HttpServer on 127.0.0.1) and a fake `security` first on PATH. Every run checks that the fixture token and
 * base64(email:token), the value of the Authorization header, are in no output and in no file of the output and
 * working directories.
 */
class ConfluenceFetchTest {
    @TempDir
    lateinit var dir: File

    private val script = File(System.getProperty("kv.scriptsDir"), "confluence_fetch.py")
    private val email = "kv-fetch@example.com"
    private val service = "kv-confluence-test"

    // A throwaway fixture in the shape of an Atlassian API token.
    private val token = "ATATT3xFfGF0kvFixtureToken0123456789abcdefABCDEF=4C2A1B0D"
    private val basic = Base64.getEncoder().encodeToString("$email:$token".toByteArray())
    private val out get() = File(dir, "out")
    private val securityCalls get() = File(dir, "security-calls.txt")
    private var server: HttpServer? = null

    /** A storage value with non-ASCII text, a literal NBSP, entities, CDATA, quotes, a backslash and both line ends. */
    private val storage =
        "<h1>C6 받기</h1>\n<p>NBSP\u00a0literal, entity&nbsp;and &mdash; \"quotes\" \\ back</p>\r\n" +
            "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[if (a < b) { x = \"y\" }]]></ac:plain-text-body></ac:structured-macro>\n" +
            "<p>emoji \ud83d\ude00</p>"

    /** One reply of the fake server. */
    private data class Reply(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    /** A request that the fake server saw: method and target, two headers, and the time in milliseconds. */
    private data class Seen(
        val request: String,
        val authorization: String?,
        val accept: String?,
        val millis: Long,
    )

    private data class Run(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    /**
     * A v2 `GET /pages/{id}` 200 body with the fields of PageSingle (SPEC.md A3). The page's own `createdAt` differs from
     * the version's. [storage] null leaves `body` empty; [value] false leaves out `body.storage.value` only.
     */
    private fun page(
        id: String,
        title: String,
        number: Long,
        storage: String?,
        value: Boolean = true,
    ): String =
        jsonOf(
            mapOf(
                "id" to id,
                "status" to "current",
                "title" to title,
                "spaceId" to "98765",
                "parentId" to "111",
                "authorId" to "557058:fixture",
                "createdAt" to "2026-09-01T10:00:00.000Z",
                "version" to mapOf("number" to number, "createdAt" to "2026-10-20T08:15:30.123Z", "message" to "", "minorEdit" to false, "authorId" to "557058:fixture"),
                "body" to if (storage == null) emptyMap() else mapOf("storage" to if (value) mapOf("representation" to "storage", "value" to storage) else mapOf("representation" to "storage")),
                "_links" to mapOf("webui" to "/spaces/KV/pages/$id", "editui" to "/pages/resumedraft.action?draftId=$id", "tinyui" to "/x/AbC", "base" to "https://kv-test.atlassian.net/wiki"),
            ),
        )

    /**
     * Starts the fake server; returns its site and the requests it sees. [pages] maps a page ID to its replies, in
     * order. A request with another path, another query or another Authorization header gets 404, 400 or 401, and a
     * request beyond the replies gets 500. Every reply that is not 200 echoes the request's Authorization header in its
     * body, so a script that printed an error body would fail the leak check.
     */
    private fun serve(vararg pages: Pair<String, List<Reply>>): Pair<String, List<Seen>> {
        val replies = pages.associate { (id, rs) -> id to ArrayDeque(rs) }
        val seen = CopyOnWriteArrayList<Seen>()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { exchange ->
            val uri = exchange.requestURI
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            seen += Seen("${exchange.requestMethod} $uri", authorization, exchange.requestHeaders.getFirst("Accept"), System.nanoTime() / 1_000_000)
            val queue = Regex("""/wiki/api/v2/pages/(\d+)""").matchEntire(uri.rawPath)?.let { replies[it.groupValues[1]] }
            val reply =
                when {
                    queue == null -> Reply(404)
                    uri.rawQuery != "body-format=storage" -> Reply(400)
                    authorization != "Basic $basic" -> Reply(401)
                    else -> queue.removeFirstOrNull() ?: Reply(500)
                }
            val body = (if (reply.status == 200) reply.body else "${reply.body} echo: $authorization").toByteArray()
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.responseHeaders.add("Content-Type", if (reply.status == 200) "application/json; charset=utf-8" else "text/plain")
            exchange.sendResponseHeaders(reply.status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}" to seen
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /**
     * Runs the script with a fake `security` first on PATH. The fake prints [token] for the item ([service], [email])
     * and fails for any other; it logs its arguments to [securityCalls]. Checks the leak rule on the result.
     */
    private fun fetch(
        site: String,
        vararg pages: String,
        keychainService: String = service,
    ): Run {
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "security").apply {
            writeText(
                """
                |#!/bin/sh
                |echo "${'$'}*" >> '${securityCalls.path}'
                |if [ "${'$'}#" -eq 6 ] && [ "${'$'}1" = find-generic-password ] && [ "${'$'}2" = -s ] && [ "${'$'}3" = '$service' ] && [ "${'$'}4" = -a ] && [ "${'$'}5" = '$email' ] && [ "${'$'}6" = -w ]; then
                |  echo '$token'
                |  exit 0
                |fi
                |echo 'security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.' >&2
                |exit 44
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        val cwd = File(dir, "cwd").apply { mkdirs() }
        val stdout = File(dir, "stdout.txt")
        val stderr = File(dir, "stderr.txt")
        val pb =
            ProcessBuilder("python3", "-I", script.path, "--site", site, "--email", email, "--keychain-service", keychainService, "--out", out.path, *pages)
                .directory(cwd)
                .redirectOutput(stdout)
                .redirectError(stderr)
        val env = pb.environment()
        env["PATH"] = bin.path + File.pathSeparator + env["PATH"]
        // The fake server is on 127.0.0.1: no proxy of the environment or of the system may take the request.
        for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
            env.remove(name)
            env.remove(name.uppercase())
        }
        env["no_proxy"] = "*"
        val p = pb.start()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "confluence_fetch.py did not finish" }
        val run = Run(p.exitValue(), stdout.readText(), stderr.readText())
        val texts = listOf("stdout" to run.stdout, "stderr" to run.stderr) + listOf(out, cwd).flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.path to it.readText() } }
        for ((name, text) in texts) {
            assertFalse(token in text, "$name holds the token")
            assertFalse(basic in text, "$name holds base64(email:token)")
        }
        return run
    }

    /** The fake server saw exactly one well-formed request per entry of [ids], in that order. */
    private fun assertRequests(
        seen: List<Seen>,
        vararg ids: String,
    ) {
        assertEquals(ids.map { "GET /wiki/api/v2/pages/$it?body-format=storage" }, seen.map { it.request })
        assertTrue(seen.all { it.authorization == "Basic $basic" }, "a request without the exact Authorization header")
        assertTrue(seen.all { it.accept == "application/json" }, seen.map { it.accept }.toString())
    }

    private fun outFiles() = out.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @Test
    fun `two pages - each gets the storage value byte for byte and the page fields, with the version time`() {
        val title = "스펙 \"C6\" 페이지"
        val (site, seen) = serve("123456" to listOf(Reply(200, page("123456", title, 3, storage))), "789" to listOf(Reply(200, page("789", "Second", 12, "<p>two</p>"))))
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val run = fetch("$site/", "123456", "789")
        val after = Instant.now()
        assertEquals(0, run.exit, run.stderr)
        assertEquals("", run.stderr)
        assertEquals("fetched 123456: $title (version 3, current)\nfetched 789: Second (version 12, current)\n", run.stdout)
        assertRequests(seen, "123456", "789")
        assertEquals(listOf("123456.page.json", "123456.xhtml", "789.page.json", "789.xhtml"), outFiles())
        assertContentEquals(storage.toByteArray(Charsets.UTF_8), File(out, "123456.xhtml").readBytes())
        assertContentEquals("<p>two</p>".toByteArray(), File(out, "789.xhtml").readBytes())
        for ((id, t, n) in listOf(Triple("123456", title, 3L), Triple("789", "Second", 12L))) {
            val text = File(out, "$id.page.json").readText()
            val fetchedAt = Regex(""""fetchedAt": "(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z)"""").find(text)?.groupValues?.get(1) ?: error(text)
            val expected = mapOf("id" to id, "title" to t, "status" to "current", "version" to n, "createdAt" to "2026-10-20T08:15:30.123Z", "site" to site, "fetchedAt" to fetchedAt)
            assertEquals(jsonOf(expected) + "\n", text)
            val at = Instant.parse(fetchedAt)
            assertTrue(!at.isBefore(before) && !at.isAfter(after), "fetchedAt $at is not between $before and $after")
        }
    }

    @Test
    fun `a fetched page goes through kv spec snapshot, and the snapshot holds no token (acceptance 3, snapshot side)`() {
        val (site, _) = serve("123456" to listOf(Reply(200, page("123456", "C6 page", 3, storage))))
        assertEquals(0, fetch(site, "123456").exit)
        val snap = File(dir, "snap")
        val blocks = writeSnapshot(snap, listOf(SnapshotInput("conf:123456", File(out, "123456.xhtml").path)))
        assertEquals(
            listOf(
                "conf:123456#c6-받기/1" to "NBSP literal, entity and — \"quotes\" \\ back",
                "conf:123456#c6-받기/2" to "if (a < b) { x = \"y\" }",
                "conf:123456#c6-받기/3" to "emoji 😀",
            ),
            blocks.map { it.anchor to it.text },
        )
        val md = File(snap, "SOURCES.md").readText()
        assertTrue("## conf:123456 (\"C6 page\", version 3)\n" in md, md)
        for (f in snap.listFiles()!!) {
            val text = f.readText()
            assertFalse(token in text || basic in text, "${f.name} holds the token or base64(email:token)")
        }
    }

    @Test
    fun `401 - an authentication message, exit 2 and no file`() {
        val (site, seen) = serve("123456" to listOf(Reply(401, "Unauthorized")))
        val run = fetch(site, "123456")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("confluence_fetch.py: page 123456: HTTP 401: authentication failed; check --email and the API token in the Keychain\n", run.stderr)
        assertRequests(seen, "123456")
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `404 stops the run - the earlier page keeps its files and the later page is not requested`() {
        val (site, seen) =
            serve(
                "1" to listOf(Reply(200, page("1", "First", 1, "<p>one</p>"))),
                "2" to listOf(Reply(404, "Not Found")),
                "3" to listOf(Reply(200, page("3", "Third", 1, "<p>three</p>"))),
            )
        val run = fetch(site, "1", "2", "3")
        assertEquals(2, run.exit, run.stderr)
        assertTrue("page 2: HTTP 404: the page was not found, or the account has no permission to view it" in run.stderr, run.stderr)
        assertTrue("stopped; 1 of 3 page(s) fetched (their files stay in ${out.path}); not fetched: 2 3" in run.stderr, run.stderr)
        assertEquals("fetched 1: First (version 1, current)\n", run.stdout)
        assertRequests(seen, "1", "2")
        assertEquals(listOf("1.page.json", "1.xhtml"), outFiles())
    }

    @Test
    fun `429 with Retry-After 0, then 200 - one retry without the 1 s backoff`() {
        val (site, seen) = serve("123456" to listOf(Reply(429, "Too Many Requests", mapOf("Retry-After" to "0")), Reply(200, page("123456", "Retried", 5, storage))))
        val run = fetch(site, "123456")
        assertEquals(0, run.exit, run.stderr)
        assertRequests(seen, "123456", "123456")
        assertTrue(seen[1].millis - seen[0].millis < 1000, "the retry waited ${seen[1].millis - seen[0].millis} ms")
        assertContentEquals(storage.toByteArray(Charsets.UTF_8), File(out, "123456.xhtml").readBytes())
        assertTrue("\"version\": 5," in File(out, "123456.page.json").readText())
    }

    @Test
    fun `429 without Retry-After - the first retry waits the 1 s backoff`() {
        val (site, seen) = serve("123456" to listOf(Reply(429), Reply(200, page("123456", "Late", 1, "<p>late</p>"))))
        val run = fetch(site, "123456")
        assertEquals(0, run.exit, run.stderr)
        assertRequests(seen, "123456", "123456")
        assertTrue(seen[1].millis - seen[0].millis >= 1000, "the retry waited ${seen[1].millis - seen[0].millis} ms")
        assertEquals(listOf("123456.page.json", "123456.xhtml"), outFiles())
    }

    @Test
    fun `503 on every try - 3 retries, then exit 2 and no file`() {
        val unavailable = Reply(503, "Service Unavailable", mapOf("Retry-After" to "0"))
        val (site, seen) = serve("123456" to List(5) { unavailable })
        val run = fetch(site, "123456")
        assertEquals(2, run.exit, run.stderr)
        assertTrue("page 123456: HTTP 503 Service Unavailable after 3 retries" in run.stderr, run.stderr)
        assertRequests(seen, "123456", "123456", "123456", "123456")
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `a response without body storage value - exit 2 and no file`() {
        val (site, seen) =
            serve(
                "10" to listOf(Reply(200, page("10", "No body", 1, null))),
                "20" to listOf(Reply(200, page("20", "No value", 1, "unused", value = false))),
            )
        for (id in listOf("10", "20")) {
            val run = fetch(site, id)
            assertEquals(2, run.exit, run.stderr)
            assertTrue("page $id: the response has no body.storage.value" in run.stderr, run.stderr)
        }
        assertRequests(seen, "10", "20")
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `plain http to another host and a page ID that is not a number are refused before the Keychain is read`() {
        val (site, seen) = serve()
        for (bad in listOf("http://kv-test.atlassian.net", "http://localhost.example.com", "http://127.0.0.1.example.com", "http://127.0.0.1@example.com", "ftp://127.0.0.1")) {
            val run = fetch(bad, "123456")
            assertEquals(2, run.exit, run.stderr)
            assertTrue("--site $bad: use https:// (plain http:// only to localhost or 127.0.0.1)" in run.stderr, run.stderr)
        }
        val run = fetch(site, "123456", "../123")
        assertEquals(2, run.exit, run.stderr)
        assertTrue("not a page ID (a page ID is a number): ../123" in run.stderr, run.stderr)
        assertFalse(securityCalls.exists(), "security was called")
        assertEquals(emptyList(), seen)
        assertFalse(out.exists())
    }

    @Test
    fun `a Keychain failure - exit 3 before any request`() {
        val (site, seen) = serve("123456" to listOf(Reply(200, page("123456", "Unused", 1, storage))))
        val run = fetch(site, "123456", keychainService = "kv-other-service")
        assertEquals(3, run.exit, run.stderr)
        assertTrue("cannot read the Keychain item with service kv-other-service and account $email: security exited 44" in run.stderr, run.stderr)
        assertEquals("find-generic-password -s kv-other-service -a $email -w\n", securityCalls.readText())
        assertEquals(emptyList(), seen)
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `a redirect is not followed, so the Authorization header goes nowhere else`() {
        // The relative location is on the fake server: a followed redirect would show as a second request.
        val (site, seen) = serve("123456" to listOf(Reply(302, headers = mapOf("Location" to "/wiki/api/v2/pages/999?body-format=storage"))))
        val run = fetch(site, "123456")
        assertEquals(2, run.exit, run.stderr)
        // The JDK server picks the reason phrase of 302.
        assertTrue(Regex("""page 123456: HTTP 302 [^:\n]*: the script does not follow redirects; check --site""").containsMatchIn(run.stderr), run.stderr)
        assertRequests(seen, "123456")
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `the wait before a retry and the site rules`() {
        val code =
            """
            |import sys; sys.dont_write_bytecode = True; sys.path.insert(0, sys.argv[1]); import confluence_fetch as c
            |cases = [("0", 0), ("7", 1), (" 5 ", 2), ("60", 0), ("61", 0), ("1847", 2), (None, 0), (None, 1), (None, 2),
            |         ("Wed, 21 Oct 2015 07:28:00 GMT", 1), ("-3", 0), ("1.5", 2), ("", 1)]
            |print(" ".join(str(c.wait_seconds(h, a)) for h, a in cases))
            |for s in sys.argv[2:]:
            |    try:
            |        print(c.check_site(s))
            |    except c.Failure as e:
            |        print("refused %d: %s" % (e.code, e))
            """.trimMargin()
        val sites =
            listOf(
                "https://kv-test.atlassian.net", "https://kv-test.atlassian.net/", "HTTPS://kv-test.atlassian.net:8443", "http://localhost:8080",
                "http://127.0.0.1:1", "http://kv-test.atlassian.net", "http://[::1]:8080", "https://user@kv-test.atlassian.net",
                "https://kv-test.atlassian.net/wiki", "https://kv-test.atlassian.net/?x=1", "https://kv-test.atlassian.net:abc", "kv-test.atlassian.net",
            )
        val (exit, out) = python("-c", code, script.parent, *sites.toTypedArray())
        assertEquals(0, exit, out)
        val scheme = "use https:// (plain http:// only to localhost or 127.0.0.1)"
        val parts = "give only the scheme and the host, as in https://SITE.atlassian.net"
        assertEquals(
            listOf(
                "0 7 5 60 60 60 1 2 4 2 1 4 2",
                "https://kv-test.atlassian.net",
                "https://kv-test.atlassian.net",
                "https://kv-test.atlassian.net:8443",
                "http://localhost:8080",
                "http://127.0.0.1:1",
                "refused 2: --site http://kv-test.atlassian.net: $scheme",
                "refused 2: --site http://[::1]:8080: $scheme",
                "refused 2: --site https://user@kv-test.atlassian.net: $parts",
                "refused 2: --site https://kv-test.atlassian.net/wiki: $parts",
                "refused 2: --site https://kv-test.atlassian.net/?x=1: $parts",
                "refused 2: --site https://kv-test.atlassian.net:abc: the port is not a number",
                "refused 2: --site kv-test.atlassian.net: $scheme",
            ),
            out.trimEnd().lines(),
        )
    }
}
