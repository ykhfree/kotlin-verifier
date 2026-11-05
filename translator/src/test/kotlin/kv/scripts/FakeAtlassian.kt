package kv.scripts

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse

/** One reply of the fake server. */
internal data class Reply(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/** A request that the fake server saw: method and target, two headers, and the time in milliseconds. */
internal data class Seen(
    val request: String,
    val authorization: String?,
    val accept: String?,
    val millis: Long,
)

internal data class Run(
    val exit: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * A fake Atlassian Cloud for a fetch script: a JDK HttpServer on 127.0.0.1 and a fake `security` first on PATH. Every
 * [run] checks that [token] and [basic], the value of the Authorization header, are in no output and in no file of the
 * output and working directories. One instance serves one test; [dir] is the test's own temporary directory.
 */
internal class FakeAtlassian(
    private val dir: File,
    private val script: File,
    private val email: String,
    private val service: String,
    private val token: String,
) {
    val basic: String = Base64.getEncoder().encodeToString("$email:$token".toByteArray())
    val out get() = File(dir, "out")
    val securityCalls get() = File(dir, "security-calls.txt")
    private var server: HttpServer? = null

    /**
     * Starts the fake server; returns its site and the requests it sees. [route] picks the reply from the request's URI
     * and Authorization header. Every reply that is not 200 echoes the request's Authorization header in its body, so a
     * script that printed an error body would fail the leak check.
     */
    fun serve(route: (URI, String?) -> Reply): Pair<String, List<Seen>> {
        val seen = CopyOnWriteArrayList<Seen>()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/") { exchange ->
            val uri = exchange.requestURI
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            seen += Seen("${exchange.requestMethod} $uri", authorization, exchange.requestHeaders.getFirst("Accept"), System.nanoTime() / 1_000_000)
            val reply = route(uri, authorization)
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

    fun stop() {
        server?.stop(0)
    }

    /**
     * Runs the script with a fake `security` first on PATH. The fake prints [token] for the item ([service], [email])
     * and fails for any other; it logs its arguments to [securityCalls]. Checks the leak rule on the result.
     */
    fun run(
        site: String,
        items: List<String>,
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
            ProcessBuilder(listOf("python3", "-I", script.path, "--site", site, "--email", email, "--keychain-service", keychainService, "--out", out.path) + items)
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
        check(p.waitFor(120, TimeUnit.SECONDS)) { "${script.name} did not finish" }
        val run = Run(p.exitValue(), stdout.readText(), stderr.readText())
        val texts = listOf("stdout" to run.stdout, "stderr" to run.stderr) + listOf(out, cwd).flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.path to it.readText() } }
        for ((name, text) in texts) {
            assertFalse(token in text, "$name holds the token")
            assertFalse(basic in text, "$name holds base64(email:token)")
        }
        return run
    }
}
