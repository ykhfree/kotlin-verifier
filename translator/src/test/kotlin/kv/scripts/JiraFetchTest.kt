package kv.scripts

import kv.json.json
import kv.json.jsonOf
import kv.json.parseJson
import kv.spec.SnapshotInput
import kv.spec.withoutFractions
import kv.spec.writeSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DESIGN-J.md 9.4: scripts/jira_fetch.py end to end, against a fake Jira Cloud and a fake `security` first on PATH
 * ([FakeAtlassian]). Every run checks that the fixture token and base64(email:token) are in no output and in no file,
 * and that the fixture's personal values and summary are not on stdout or stderr (P8, P9).
 */
class JiraFetchTest {
    @TempDir
    lateinit var dir: File

    private var script = File(System.getProperty("kv.scriptsDir"), "jira_fetch.py")
    private val email = "kv-jira-fetch@example.com"
    private val service = "kv-jira-test"

    // A throwaway fixture in the shape of an Atlassian API token.
    private val token = "ATATT3xFfGF0kvJiraFixtureToken0123456789abcdefABCDEF=7E3D9A1C"
    private val fake by lazy { FakeAtlassian(dir, script, email, service, token) }
    private val out get() = fake.out

    private val updated = "2026-11-01T10:00:00.000+0900"

    // Fixture values that the file holds and that stdout and stderr must not hold (DESIGN-J.md 6.1).
    private val summary = "요약: 고객 \"VIP\" 결제 오류 \\ \ud83d\ude00"
    private val person = "홍길동 Fixture"
    private val personEmail = "hong.fixture@example.com"
    private val personPhone = "010-1234-5678"
    private val accountId = "557058:fixture-account"

    private fun adf(text: String): Map<String, Any?> =
        mapOf(
            "type" to "doc",
            "version" to 1,
            "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))),
        )

    private val user = mapOf("accountId" to accountId, "displayName" to person, "emailAddress" to personEmail)

    /** A Get issue 200 body. The fields hold what the file must keep and the outputs must not show. */
    private fun issue(
        id: Any? = "10042",
        key: String = "KV-7",
        summary: String = this.summary,
    ): Map<String, Any?> =
        mapOf(
            "expand" to "names,schema",
            "id" to id,
            "self" to "https://kv-test.atlassian.net/rest/api/3/issue/$id",
            "key" to key,
            "names" to mapOf("summary" to "Summary", "customfield_10050" to "수용 기준"),
            "schema" to mapOf("summary" to mapOf("type" to "string", "system" to "summary")),
            "fields" to
                mapOf(
                    "summary" to summary,
                    "updated" to updated,
                    "reporter" to user,
                    "description" to adf("연락처 $personPhone, 본문의 문단"),
                    "customfield_10050" to adf("수용 기준 문단"),
                ),
        )

    private fun comment(
        id: String,
        text: String = "댓글 $id",
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "author" to user,
            "body" to adf(text),
            "created" to "2026-10-20T09:00:00.000+0900",
            "updated" to "2026-10-20T09:00:00.000+0900",
        )

    private val links =
        listOf(mapOf("id" to 10000, "self" to "https://kv-test.atlassian.net/rest/api/3/issue/10042/remotelink/10000", "object" to mapOf("url" to "https://example.com/spec", "title" to "원격 명세")))

    private fun ok(value: Any?) = Reply(200, jsonOf(value))

    private fun commentsPage(
        comments: List<Map<String, Any?>>,
        total: Int,
        startAt: Int = 0,
    ) = ok(mapOf("startAt" to startAt, "maxResults" to 100, "total" to total, "comments" to comments))

    private fun issueTarget(issue: String) = "/rest/api/3/issue/$issue?fields=*all,-comment&expand=names,schema&failFast=true"

    private fun linksTarget(id: String = "10042") = "/rest/api/3/issue/$id/remotelink"

    private fun commentsTarget(
        startAt: Int,
        id: String = "10042",
    ) = "/rest/api/3/issue/$id/comment?startAt=$startAt&maxResults=100&orderBy=created"

    /**
     * Starts the fake server; returns its site and the requests it sees. [routes] maps a request target to its replies,
     * in order. A request with another target or another Authorization header gets 404 or 401, and a request beyond the
     * replies gets 500.
     */
    private fun serve(vararg routes: Pair<String, List<Reply>>): Pair<String, List<Seen>> {
        val replies = routes.associate { (target, rs) -> target to ArrayDeque(rs) }
        return fake.serve { uri, authorization ->
            val queue = replies[uri.toString()]
            when {
                queue == null -> Reply(404)
                authorization != "Basic ${fake.basic}" -> Reply(401)
                else -> queue.removeFirstOrNull() ?: Reply(500)
            }
        }
    }

    @AfterEach
    fun stop() {
        fake.stop()
    }

    private fun fetch(
        site: String,
        vararg issues: String,
        keychainService: String = service,
    ): Run {
        val run = fake.run(site, issues.toList(), keychainService)
        for (value in listOf(summary, person, personEmail, personPhone, accountId)) {
            assertFalse(value in run.stdout, "stdout holds $value")
            assertFalse(value in run.stderr, "stderr holds $value")
        }
        return run
    }

    /** The fake server saw exactly one well-formed GET per entry of [targets], in that order. */
    private fun assertRequests(
        seen: List<Seen>,
        vararg targets: String,
    ) {
        assertEquals(targets.map { "GET $it" }, seen.map { it.request })
        assertTrue(seen.all { it.authorization == "Basic ${fake.basic}" }, "a request without the exact Authorization header")
        assertTrue(seen.all { it.accept == "application/json" }, seen.map { it.accept }.toString())
    }

    private fun outFiles() = out.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun written(id: String = "10042") = parseJson(File(out, "$id.jira.json").readText()) as Map<*, *>

    private fun commentIds(document: Map<*, *>) = (document["comments"] as List<*>).map { (it as Map<*, *>)["id"] }

    private fun stdoutOf(
        issue: String,
        comments: Int,
        id: String = "10042",
    ) = "fetched $issue: id $id, $comments comment(s), updated $updated\nsnapshot input: jira:$id=${out.path}/$id.jira.json\n"

    @Test
    fun `F1 one issue with 2 comments - four exact requests, the file holds the responses, stdout holds no summary`() {
        val comments = listOf(comment("10072", "첫 댓글 $personEmail"), comment("10075", "둘째 댓글"))
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(comments, total = 2)),
                commentsTarget(2) to listOf(commentsPage(emptyList(), total = 2, startAt = 2)),
            )
        val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val run = fetch("$site/", "KV-7")
        val after = Instant.now()
        assertEquals(0, run.exit, run.stderr)
        assertEquals("", run.stderr)
        assertEquals("fetched KV-7: id 10042, 2 comment(s), updated $updated\nsnapshot input: jira:10042=${out.path}/10042.jira.json\n", run.stdout)
        assertRequests(
            seen,
            "/rest/api/3/issue/KV-7?fields=*all,-comment&expand=names,schema&failFast=true",
            "/rest/api/3/issue/10042/remotelink",
            "/rest/api/3/issue/10042/comment?startAt=0&maxResults=100&orderBy=created",
            "/rest/api/3/issue/10042/comment?startAt=2&maxResults=100&orderBy=created",
        )
        assertEquals(listOf("10042.jira.json"), outFiles())
        val text = File(out, "10042.jira.json").readBytes().toString(Charsets.UTF_8)
        val fetchedAt = Regex(""""fetchedAt": "(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z)"""").find(text)?.groupValues?.get(1) ?: error(text)
        val at = Instant.parse(fetchedAt)
        assertTrue(!at.isBefore(before) && !at.isAfter(after), "fetchedAt $at is not between $before and $after")
        val expected =
            mapOf(
                "format" to "kv-jira-issue/1",
                "requested" to "KV-7",
                "site" to site,
                "fetchedAt" to fetchedAt,
                "issue" to issue(),
                "remoteLinks" to links,
                "comments" to comments,
            )
        assertEquals(jsonOf(expected) + "\n", text)
        assertFalse(email in text, "the file holds --email")
        for (value in listOf(summary, person, personEmail, personPhone, accountId)) {
            assertTrue(json(value).removeSurrounding("\"") in text, "the file does not hold $value")
        }
    }

    @Test
    fun `F2 two issues - two files and two pairs of lines, in the given order`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("10072")), total = 1)),
                commentsTarget(1) to listOf(commentsPage(emptyList(), total = 1, startAt = 1)),
                issueTarget("KV-8") to listOf(ok(issue(id = "10043", key = "KV-8"))),
                linksTarget("10043") to listOf(ok(emptyList<Any>())),
                commentsTarget(0, "10043") to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "KV-7", "KV-8")
        assertEquals(0, run.exit, run.stderr)
        assertEquals(stdoutOf("KV-7", 1) + stdoutOf("KV-8", 0, "10043"), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(1), issueTarget("KV-8"), linksTarget("10043"), commentsTarget(0, "10043"))
        assertEquals(listOf("10042.jira.json", "10043.jira.json"), outFiles())
        assertEquals("KV-7", written()["requested"])
        assertEquals(listOf<Any?>("10072"), commentIds(written()))
        assertEquals("KV-8", written("10043")["requested"])
        assertEquals(emptyList<Any?>(), written("10043")["remoteLinks"])
    }

    @Test
    fun `F3 a moved key - the later requests use the id, one notice, exit 0, the file keeps both keys`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue(key = "XYZ-3"))),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("KV-7: Jira returned key XYZ-3 (id 10042); the key differs in case or the issue moved; anchors use the id, so they stay\n", run.stderr)
        assertEquals(stdoutOf("KV-7", 0), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0))
        assertEquals("KV-7", written()["requested"])
        assertEquals("XYZ-3", (written()["issue"] as Map<*, *>)["key"])
    }

    @Test
    fun `F4 a key that differs in case - one notice, exit 0`() {
        val (site, seen) =
            serve(
                issueTarget("kv-7") to listOf(ok(issue(key = "KV-7"))),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "kv-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("kv-7: Jira returned key KV-7 (id 10042); the key differs in case or the issue moved; anchors use the id, so they stay\n", run.stderr)
        assertRequests(seen, issueTarget("kv-7"), linksTarget(), commentsTarget(0))
        assertEquals("kv-7", written()["requested"])
    }

    @Test
    fun `a numeric ISSUE gets no key notice, because it is not a key`() {
        val (site, seen) =
            serve(
                issueTarget("10042") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "10042")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("", run.stderr)
        assertEquals(stdoutOf("10042", 0), run.stdout)
        assertRequests(seen, issueTarget("10042"), linksTarget(), commentsTarget(0))
        assertEquals("10042", written()["requested"])
    }

    @Test
    fun `F5 two pages with total as the count of the page - every comment arrives`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1"), comment("2")), total = 2)),
                commentsTarget(2) to listOf(commentsPage(listOf(comment("3")), total = 1, startAt = 2)),
                commentsTarget(3) to listOf(commentsPage(emptyList(), total = 0, startAt = 3)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals(stdoutOf("KV-7", 3), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(2), commentsTarget(3))
        assertEquals(listOf<Any?>("1", "2", "3"), commentIds(written()))
    }

    @Test
    fun `F6 two pages with total as the count of all pages - every comment arrives`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1"), comment("2")), total = 3)),
                commentsTarget(2) to listOf(commentsPage(listOf(comment("3")), total = 3, startAt = 2)),
                commentsTarget(3) to listOf(commentsPage(emptyList(), total = 3, startAt = 3)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(2), commentsTarget(3))
        assertEquals(listOf<Any?>("1", "2", "3"), commentIds(written()))
    }

    @Test
    fun `F7 pages smaller than maxResults - startAt grows by the count received`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1"), comment("2"), comment("3")), total = 5)),
                commentsTarget(3) to listOf(commentsPage(listOf(comment("4")), total = 5, startAt = 3)),
                commentsTarget(4) to listOf(commentsPage(listOf(comment("5")), total = 5, startAt = 4)),
                commentsTarget(5) to listOf(commentsPage(emptyList(), total = 5, startAt = 5)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(3), commentsTarget(4), commentsTarget(5))
        assertEquals(listOf<Any?>("1", "2", "3", "4", "5"), commentIds(written()))
    }

    @Test
    fun `F8 no comment - one comment request, exit 0`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals(stdoutOf("KV-7", 0), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0))
        assertEquals(emptyList<Any?>(), written()["comments"])
    }

    @Test
    fun `F9 an empty page with a total above the count received - exit 2 and no file`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1"), comment("2")), total = 5)),
                commentsTarget(2) to listOf(commentsPage(emptyList(), total = 5, startAt = 2)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: got 2 comment(s), but the last response has total 5\n", run.stderr)
        assertEquals("", run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(2))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F9 an empty first page with a total above zero - exit 2 and no file`() {
        val (site, _) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 1)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: got 0 comment(s), but the last response has total 1\n", run.stderr)
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F10 a server that ignores startAt - exit 2, no file, and the run stops at the second comment request`() {
        val same = commentsPage(listOf(comment("1"), comment("2")), total = 2)
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(same),
                commentsTarget(2) to listOf(same),
                commentsTarget(4) to listOf(same),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: comment 1 came twice; the server can ignore startAt\n", run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(2))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `a comment id twice in one page is an error too`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1"), comment("1")), total = 2)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertTrue("comment 1 came twice" in run.stderr, run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F11 a 500 on the second comment page - exit 2, no new file, and the old file stays`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to List(2) { ok(issue()) },
                linksTarget() to List(2) { ok(links) },
                commentsTarget(0) to List(2) { commentsPage(listOf(comment("1"), comment("2")), total = 3) },
                commentsTarget(2) to List(2) { Reply(500, "Internal Server Error") },
            )
        val oldFile = File(out.apply { mkdirs() }, "10042.jira.json").apply { writeText("the file of an earlier run\n") }
        val withOld = fetch(site, "KV-7")
        assertEquals(2, withOld.exit, withOld.stderr)
        assertEquals("jira_fetch.py: issue KV-7: HTTP 500 Internal Server Error\n", withOld.stderr)
        assertEquals("the file of an earlier run\n", oldFile.readText())
        assertEquals(listOf("10042.jira.json"), outFiles())

        oldFile.delete()
        val withoutOld = fetch(site, "KV-7")
        assertEquals(2, withoutOld.exit, withoutOld.stderr)
        assertEquals(emptyList(), outFiles())
        val oneRun = arrayOf(issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(2))
        assertRequests(seen, *oneRun, *oneRun)
    }

    @Test
    fun `F12 401 - an authentication message, exit 2 and no file`() {
        val (site, seen) = serve(issueTarget("KV-7") to listOf(Reply(401, "Unauthorized")))
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: HTTP 401: authentication failed; check --email and the API token in the Keychain\n", run.stderr)
        assertRequests(seen, issueTarget("KV-7"))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F13 404 - an issue message, exit 2 and no file`() {
        val (site, seen) = serve(issueTarget("KV-7") to listOf(Reply(404, "Not Found")))
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: HTTP 404: the issue was not found, or the account has no permission to view it\n", run.stderr)
        assertRequests(seen, issueTarget("KV-7"))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `a failed issue stops the run - the earlier file stays and the later issue is not requested`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
                issueTarget("KV-8") to listOf(Reply(404)),
                issueTarget("KV-9") to listOf(ok(issue(id = "10044", key = "KV-9"))),
            )
        val run = fetch(site, "KV-7", "KV-8", "KV-9")
        assertEquals(2, run.exit, run.stderr)
        assertEquals(
            "jira_fetch.py: issue KV-8: HTTP 404: the issue was not found, or the account has no permission to view it\n" +
                "jira_fetch.py: stopped; 1 of 3 issue(s) fetched (their files stay in ${out.path}); not fetched: KV-8 KV-9\n",
            run.stderr,
        )
        assertEquals(stdoutOf("KV-7", 0), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), issueTarget("KV-8"))
        assertEquals(listOf("10042.jira.json"), outFiles())
    }

    @Test
    fun `another status on the issue request names a possible failFast failure, and the named statuses do not`() {
        val (site, _) =
            serve(
                issueTarget("KV-1") to listOf(Reply(500)),
                issueTarget("KV-2") to listOf(Reply(400)),
                issueTarget("KV-3") to listOf(Reply(403)),
            )
        for ((issue, status) in listOf("KV-1" to "500 Internal Server Error", "KV-2" to "400 Bad Request", "KV-3" to "403 Forbidden")) {
            val run = fetch(site, issue)
            assertEquals(2, run.exit, run.stderr)
            assertEquals("jira_fetch.py: issue $issue: HTTP $status; with failFast=true, one field that failed to load can be the reason\n", run.stderr)
            assertEquals(emptyList(), outFiles())
        }
    }

    @Test
    fun `F14 a 429 with Retry-After 0 on a comment page, then 200 - one retry without the 1 s backoff`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(Reply(429, "Too Many Requests", mapOf("Retry-After" to "0")), commentsPage(listOf(comment("1")), total = 1)),
                commentsTarget(1) to listOf(commentsPage(emptyList(), total = 1, startAt = 1)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("", run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(0), commentsTarget(1))
        assertTrue(seen[3].millis - seen[2].millis < 1000, "the retry waited ${seen[3].millis - seen[2].millis} ms")
        assertEquals(listOf<Any?>("1"), commentIds(written()))
    }

    @Test
    fun `F15 503 on every try - 3 retries, then exit 2 and no file`() {
        val unavailable = Reply(503, "Service Unavailable", mapOf("Retry-After" to "0"))
        val (site, seen) = serve(issueTarget("KV-7") to List(5) { unavailable })
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: HTTP 503 Service Unavailable after 3 retries\n", run.stderr)
        assertRequests(seen, issueTarget("KV-7"), issueTarget("KV-7"), issueTarget("KV-7"), issueTarget("KV-7"))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F16 a redirect on the issue request and on a comment request is not followed`() {
        // Each location is on the fake server with a good reply: a followed redirect would show as one more request.
        val elsewhere = "/rest/api/3/elsewhere"
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(Reply(302, headers = mapOf("Location" to elsewhere))),
                issueTarget("KV-8") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(Reply(302, headers = mapOf("Location" to elsewhere))),
                elsewhere to listOf(ok(issue()), ok(issue())),
            )
        val redirect = Regex("""issue KV-[78]: HTTP 302 [^:\n;]*: the script does not follow redirects; check --site\n""")
        val first = fetch(site, "KV-7")
        assertEquals(2, first.exit, first.stderr)
        assertTrue(redirect.containsMatchIn(first.stderr), first.stderr)
        assertRequests(seen, issueTarget("KV-7"))
        val second = fetch(site, "KV-8")
        assertEquals(2, second.exit, second.stderr)
        assertTrue(redirect.containsMatchIn(second.stderr), second.stderr)
        assertRequests(seen, issueTarget("KV-7"), issueTarget("KV-8"), linksTarget(), commentsTarget(0))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F17 a bad site and a bad ISSUE are refused before the Keychain is read`() {
        val (site, seen) = serve()
        for (bad in listOf("http://kv-test.atlassian.net", "http://127.0.0.1@example.com", "https://kv-test.atlassian.net/wiki", "ftp://127.0.0.1")) {
            val run = fetch(bad, "KV-7")
            assertEquals(2, run.exit, run.stderr)
            assertTrue("jira_fetch.py: --site $bad: " in run.stderr, run.stderr)
        }
        assertEquals("jira_fetch.py: not an issue key or a numeric id: ../1\n", fetch(site, "KV-7", "../1", "KV-8").stderr)
        for (bad in listOf("../1", "KV-7/../1", "KV-7?x=1", "KV 7", "KV_7", "KV-", "KV-7a", "키-7", "10042x")) {
            val run = fetch(site, "KV-7", bad, "KV-8")
            assertEquals(2, run.exit, run.stderr)
            assertTrue(run.stderr.startsWith("jira_fetch.py: not an issue key or a numeric id: "), run.stderr)
            assertFalse("KV-8" in run.stderr, run.stderr)
        }
        assertFalse(fake.securityCalls.exists(), "security was called")
        assertEquals(emptyList(), seen)
        assertFalse(out.exists())
    }

    @Test
    fun `F17 a key with lower case, digits and an underscore passes the check`() {
        val (site, seen) =
            serve(
                issueTarget("A_1b-12") to listOf(ok(issue(key = "A_1b-12"))),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "A_1b-12")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("", run.stderr)
        assertRequests(seen, issueTarget("A_1b-12"), linksTarget(), commentsTarget(0))
    }

    @Test
    fun `F18 a Keychain failure - exit 3 before any request`() {
        val (site, seen) = serve(issueTarget("KV-7") to listOf(ok(issue())))
        val run = fetch(site, "KV-7", keychainService = "kv-other-service")
        assertEquals(3, run.exit, run.stderr)
        assertTrue("jira_fetch.py: cannot read the Keychain item with service kv-other-service and account $email: security exited 44" in run.stderr, run.stderr)
        assertEquals("find-generic-password -s kv-other-service -a $email -w\n", fake.securityCalls.readText())
        assertEquals(emptyList(), seen)
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F19 an issue response without id, fields, names, schema or key - exit 2, no file, no later request`() {
        val missing = listOf("id", "fields", "names", "schema", "key")
        val (site, seen) = serve(issueTarget("KV-7") to missing.map { ok(issue() - it) })
        for (name in missing) {
            val run = fetch(site, "KV-7")
            assertEquals(2, run.exit, run.stderr)
            assertEquals("jira_fetch.py: issue KV-7: the response has no $name\n", run.stderr)
            assertEquals(emptyList(), outFiles())
        }
        assertRequests(seen, *Array(missing.size) { issueTarget("KV-7") })
    }

    @Test
    fun `F19 a comment page without comments - exit 2 and no file`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(ok(mapOf("startAt" to 0, "maxResults" to 100, "total" to 0))),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: the response has no comments\n", run.stderr)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0))
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `an issue without updated is fetched, because the script only shows the value`() {
        // DESIGN-J.md U6: the real value is unconfirmed, so it must not stop a fetch.
        val noUpdated = issue().mapValues { (k, v) -> if (k == "fields") (v as Map<*, *>) - "updated" else v }
        val (site, _) =
            serve(
                issueTarget("KV-7") to listOf(ok(noUpdated)),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("fetched KV-7: id 10042, 0 comment(s)\nsnapshot input: jira:10042=${out.path}/10042.jira.json\n", run.stdout)
    }

    @Test
    fun `F22 the fixture fetched through the script gives the same blocks, lines, warnings and SOURCES_md, with no token`() {
        // DESIGN-J.md F22 and P8. The same text in and out: this finds any drift between the hand-made fixture and what
        // the script really writes, line numbers too. The server sends the fixture's parts in pages of 4.
        val fixture = File(javaClass.getResource("/jira/10042.jira.json")!!.toURI())
        val root = withoutFractions(parseJson(fixture.readText())) as Map<*, *>
        val comments = (root["comments"] as List<*>).map { it as Map<String, Any?> }
        val first = comments.take(4)
        val rest = comments.drop(4)
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(root["issue"])),
                linksTarget() to listOf(ok(root["remoteLinks"])),
                commentsTarget(0) to listOf(commentsPage(first, total = comments.size)),
                commentsTarget(4) to listOf(commentsPage(rest, total = comments.size, startAt = 4)),
                commentsTarget(comments.size) to listOf(commentsPage(emptyList(), total = comments.size, startAt = comments.size)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals(5, seen.size)
        for (value in listOf("Fixture Person Alpha", "fixture.alpha@example.com", "010-0000-0000", "fixture-account-0001", "잠금 만료 규칙 변경")) {
            assertFalse(value in run.stdout + run.stderr, "$value is on stdout or stderr")
        }

        fun snapshot(
            file: File,
            name: String,
        ): Triple<List<List<Any>>, List<String>, String> {
            val warnings = mutableListOf<String>()
            val snap = File(dir, name)
            val blocks = writeSnapshot(snap, listOf(SnapshotInput("jira:10042", file.path))) { warnings += it }
            val sources = File(snap, "sources.json").readText()
            val md = File(snap, "SOURCES.md").readText()
            assertFalse(token in sources + md + warnings, "the snapshot holds the token")
            assertFalse(fake.basic in sources + md + warnings, "the snapshot holds base64(email:token)")
            return Triple(blocks.map { listOf(it.anchor, it.text, it.sha256, it.first, it.last) }, warnings, md)
        }
        val fetched = snapshot(File(out, "10042.jira.json"), "fetched")
        val direct = snapshot(fixture, "direct")
        assertEquals(43, fetched.first.size)
        assertEquals(direct.first, fetched.first)
        assertEquals(direct.second, fetched.second)
        assertEquals(direct.third, fetched.third)
    }

    @Test
    fun `other malformed responses - exit 2 and no file, never a Python traceback`() {
        val cases =
            listOf(
                "fields is not an object" to ok(issue() + ("fields" to "not an object")),
                "the response is not JSON" to Reply(200, "<html>a login page</html>"),
                "the response has no id" to ok(listOf(issue())),
            )
        val pages =
            listOf(
                "the response has no total" to ok(mapOf("comments" to emptyList<Any>())),
                "got 0 comment(s), but the last response has total many" to ok(mapOf("total" to "many", "comments" to emptyList<Any>())),
                "comments is not an array" to ok(mapOf("total" to 1, "comments" to "not an array")),
                "the response has no id" to ok(mapOf("total" to 1, "comments" to listOf(mapOf("body" to "no id")))),
                "the response has no id" to ok(mapOf("total" to 1, "comments" to listOf("not an object"))),
                "a comment id is not a string" to ok(mapOf("total" to 1, "comments" to listOf(mapOf("id" to listOf("10072"))))),
                "the response is not JSON" to Reply(200, "not JSON"),
            )
        val (site, _) =
            serve(
                issueTarget("KV-1") to cases.map { it.second },
                issueTarget("KV-2") to pages.map { ok(issue(key = "KV-2")) },
                linksTarget() to pages.map { ok(links) },
                commentsTarget(0) to pages.map { it.second },
            )
        for ((message, _) in cases) {
            val run = fetch(site, "KV-1")
            assertEquals(2, run.exit, run.stderr)
            assertEquals("jira_fetch.py: issue KV-1: $message\n", run.stderr)
        }
        for ((message, _) in pages) {
            val run = fetch(site, "KV-2")
            assertEquals(2, run.exit, run.stderr)
            assertEquals("jira_fetch.py: issue KV-2: $message\n", run.stderr)
        }
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F20 a 403 on the remote links - null, one notice, the comments follow, exit 0`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(Reply(403, "Forbidden")),
                commentsTarget(0) to listOf(commentsPage(listOf(comment("1")), total = 1)),
                commentsTarget(1) to listOf(commentsPage(emptyList(), total = 1, startAt = 1)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(0, run.exit, run.stderr)
        assertEquals("KV-7: HTTP 403 on the remote links; issue linking can be disabled; remoteLinks is null\n", run.stderr)
        assertEquals(stdoutOf("KV-7", 1), run.stdout)
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), commentsTarget(0), commentsTarget(1))
        val document = written()
        assertTrue("remoteLinks" in document)
        assertEquals(null, document["remoteLinks"])
        assertEquals(listOf<Any?>("1"), commentIds(document))
    }

    @Test
    fun `F20 a 404 or a 500 on the remote links - exit 2, no file, no comment request`() {
        val (site, seen) =
            serve(
                issueTarget("KV-7") to List(2) { ok(issue()) },
                linksTarget() to listOf(Reply(404), Reply(500)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        for (message in listOf("HTTP 404: the issue was not found, or the account has no permission to view it", "HTTP 500 Internal Server Error")) {
            val run = fetch(site, "KV-7")
            assertEquals(2, run.exit, run.stderr)
            assertEquals("jira_fetch.py: issue KV-7: $message\n", run.stderr)
            assertEquals(emptyList(), outFiles())
        }
        assertRequests(seen, issueTarget("KV-7"), linksTarget(), issueTarget("KV-7"), linksTarget())
    }

    @Test
    fun `a 403 on a comment page stays an error - only the remote links may be null`() {
        val (site, _) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(Reply(403)),
            )
        val run = fetch(site, "KV-7")
        assertEquals(2, run.exit, run.stderr)
        assertEquals("jira_fetch.py: issue KV-7: HTTP 403 Forbidden\n", run.stderr)
        assertEquals(emptyList(), outFiles())
    }

    @Test
    fun `F21 an id that is not a number - exit 2, no file, no later request`() {
        val ids = listOf("10042x", "../../etc/x", "", "١٢٣", 10042, null)
        val (site, seen) = serve(issueTarget("KV-7") to ids.map { ok(issue(id = it)) })
        for (id in ids) {
            val run = fetch(site, "KV-7")
            assertEquals(2, run.exit, "id $id: ${run.stderr}")
            assertEquals("jira_fetch.py: issue KV-7: the id of the response is not a number\n", run.stderr)
            assertEquals(emptyList(), outFiles())
        }
        assertRequests(seen, *Array(ids.size) { issueTarget("KV-7") })
    }

    @Test
    fun `an unpaired surrogate in a response - exit 2 and no file, and a valid pair is written`() {
        val (site, _) =
            serve(
                issueTarget("KV-1") to listOf(Reply(200, jsonOf(issue(key = "KV-1", summary = "SURROGATE")).replace("SURROGATE", "\\ud83d"))),
                issueTarget("KV-2") to listOf(Reply(200, jsonOf(issue(key = "KV-2", summary = "SURROGATE")).replace("SURROGATE", "\\ud83d\\ude00"))),
                linksTarget() to List(2) { ok(links) },
                commentsTarget(0) to List(2) { commentsPage(emptyList(), total = 0) },
            )
        val oldFile = File(out.apply { mkdirs() }, "10042.jira.json").apply { writeText("the file of an earlier run\n") }
        val bad = fetch(site, "KV-1")
        assertEquals(2, bad.exit, bad.stderr)
        assertEquals("jira_fetch.py: issue KV-1: a response holds an unpaired surrogate, so it cannot be written in UTF-8\n", bad.stderr)
        assertEquals("the file of an earlier run\n", oldFile.readText())
        val good = fetch(site, "KV-2")
        assertEquals(0, good.exit, good.stderr)
        assertEquals("\ud83d\ude00", ((written()["issue"] as Map<*, *>)["fields"] as Map<*, *>)["summary"])
    }

    @Test
    fun `the script loads confluence_fetch py under python3 -I and adds no bytecode file next to it`() {
        // A private copy of the scripts: no other run can have left a __pycache__ next to it.
        val scripts = File(dir, "scripts").apply { mkdirs() }
        for (name in listOf("jira_fetch.py", "confluence_fetch.py")) File(script.parentFile, name).copyTo(File(scripts, name))
        script = File(scripts, "jira_fetch.py")
        val (site, _) =
            serve(
                issueTarget("KV-7") to listOf(ok(issue())),
                linksTarget() to listOf(ok(links)),
                commentsTarget(0) to listOf(commentsPage(emptyList(), total = 0)),
            )
        assertEquals(0, fetch(site, "KV-7").exit)
        assertEquals(listOf("confluence_fetch.py", "jira_fetch.py"), scripts.listFiles()!!.map { it.name }.sorted())
    }
}
