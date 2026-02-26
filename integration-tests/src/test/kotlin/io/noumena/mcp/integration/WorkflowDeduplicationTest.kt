package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.security.MessageDigest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkflowDeduplicationTest {

    private lateinit var adminToken: String
    private lateinit var gatewayToken: String
    private lateinit var client: HttpClient
    private lateinit var workflowId: String

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @BeforeAll
    fun setup() = runBlocking {
        client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }

        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }

        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token (pAdmin)")
        gatewayToken = KeycloakAuth.getToken("gateway", "Welcome123")
        println("    ✓ Gateway token (pGateway)")

        workflowId = ensureWorkflow("dedup-test-service")
        println("    ✓ Workflow instance: $workflowId (service=dedup-test-service)")

        callAdmin("setRequiresWorkflow", """{"toolName": "transfer_funds", "required": true}""")
        println("    ✓ transfer_funds flagged as requiresWorkflow=true")
    }

    @Test
    @Order(1)
    fun `same fingerprint returns same requestId`() = runBlocking {
        val args = """{"amount":"500","currency":"EUR","to":"supplier-42"}"""
        val fp = fingerprintOf(args)

        val r1 = callEvaluate("transfer_funds", "buyer@acme.com", args, fp)
        val r2 = callEvaluate("transfer_funds", "buyer@acme.com", args, fp)

        assertEquals("pending", r1["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", r2["decision"]?.jsonPrimitive?.content)
        assertEquals(
            r1["requestId"]?.jsonPrimitive?.content,
            r2["requestId"]?.jsonPrimitive?.content,
            "Same fingerprint must return same pending requestId"
        )
        println("    ✓ Same fingerprint → same requestId (${r1["requestId"]?.jsonPrimitive?.content})")
    }

    @Test
    @Order(2)
    fun `logically identical JSON with different whitespace deduplicates correctly`() = runBlocking {
        val compactArgs = """{"amount":"200","currency":"USD"}"""
        val prettyArgs = """{ "amount" : "200", "currency" : "USD" }"""
        val reorderedArgs = """{"currency":"USD","amount":"200"}"""

        val canonicalArgs = canonicalJson(mapOf("amount" to "200", "currency" to "USD"))
        val fp = fingerprintOf(canonicalArgs)

        val r1 = callEvaluate("transfer_funds", "buyer2@acme.com", compactArgs, fp)
        val r2 = callEvaluate("transfer_funds", "buyer2@acme.com", prettyArgs, fp)
        val r3 = callEvaluate("transfer_funds", "buyer2@acme.com", reorderedArgs, fp)

        val id1 = r1["requestId"]?.jsonPrimitive?.content
        val id2 = r2["requestId"]?.jsonPrimitive?.content
        val id3 = r3["requestId"]?.jsonPrimitive?.content

        assertEquals("pending", r1["decision"]?.jsonPrimitive?.content)
        assertEquals(id1, id2, "Compact and pretty-printed JSON must deduplicate to same request")
        assertEquals(id1, id3, "Key-reordered JSON must deduplicate to same request")
    }

    @Test
    @Order(3)
    fun `different fingerprint creates new request`() = runBlocking {
        val args1 = canonicalJson(mapOf("amount" to "100", "currency" to "EUR"))
        val args2 = canonicalJson(mapOf("amount" to "999", "currency" to "EUR"))

        val r1 = callEvaluate("transfer_funds", "buyer3@acme.com", args1, fingerprintOf(args1))
        val r2 = callEvaluate("transfer_funds", "buyer3@acme.com", args2, fingerprintOf(args2))

        assertEquals("pending", r1["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", r2["decision"]?.jsonPrimitive?.content)
        assertNotEquals(
            r1["requestId"]?.jsonPrimitive?.content,
            r2["requestId"]?.jsonPrimitive?.content,
            "Different arguments must produce separate pending requests"
        )
    }

    @Test
    @Order(4)
    fun `same fingerprint with different callerIdentity creates separate requests`() = runBlocking {
        val args = canonicalJson(mapOf("amount" to "50", "currency" to "EUR"))
        val fp = fingerprintOf(args)

        val alice = callEvaluate("transfer_funds", "alice@acme.com", args, fp)
        val bob   = callEvaluate("transfer_funds", "bob@acme.com",   args, fp)

        assertEquals("pending", alice["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", bob["decision"]?.jsonPrimitive?.content)
        assertNotEquals(
            alice["requestId"]?.jsonPrimitive?.content,
            bob["requestId"]?.jsonPrimitive?.content,
            "Different callers must have separate pending requests even with identical arguments"
        )
    }

    @Test
    @Order(5)
    fun `same fingerprint with different toolName creates separate requests`() = runBlocking {
        callAdmin("setRequiresWorkflow", """{"toolName": "approve_invoice", "required": true}""")

        val args = canonicalJson(mapOf("id" to "INV-001"))
        val fp = fingerprintOf(args)

        val r1 = callEvaluate("transfer_funds",  "buyer4@acme.com", args, fp)
        val r2 = callEvaluate("approve_invoice", "buyer4@acme.com", args, fp)

        assertEquals("pending", r1["decision"]?.jsonPrimitive?.content)
        assertEquals("pending", r2["decision"]?.jsonPrimitive?.content)
        assertNotEquals(
            r1["requestId"]?.jsonPrimitive?.content,
            r2["requestId"]?.jsonPrimitive?.content,
            "Different tools must have separate pending requests even with identical arguments"
        )
    }

    @Test
    @Order(6)
    fun `approve then re-evaluate returns allow then new pending`() = runBlocking {
        val args = canonicalJson(mapOf("amount" to "777", "currency" to "EUR"))
        val fp = fingerprintOf(args)
        val caller = "buyer5@acme.com"

        val pending = callEvaluate("transfer_funds", caller, args, fp)
        assertEquals("pending", pending["decision"]?.jsonPrimitive?.content)
        val requestId = pending["requestId"]!!.jsonPrimitive.content

        callAdmin("approve", """{"requestId": "$requestId"}""")
        println("    ✓ Approved $requestId")

        // Re-evaluate with same fingerprint → allow (and consume)
        val allowed = callEvaluate("transfer_funds", caller, args, fp)
        assertEquals("allow", allowed["decision"]?.jsonPrimitive?.content,
            "After approval, evaluate must return allow")
        assertEquals(requestId, allowed["requestId"]?.jsonPrimitive?.content)
        println("    ✓ Re-evaluate → allow (consumed)")

        // Third evaluate → new pending (approval was one-shot)
        val newPending = callEvaluate("transfer_funds", caller, args, fp)
        assertEquals("pending", newPending["decision"]?.jsonPrimitive?.content,
            "After consumption, evaluate must create new pending request")
        assertNotEquals(requestId, newPending["requestId"]?.jsonPrimitive?.content,
            "New pending must have a fresh requestId")
        println("    ✓ Post-consumption → new pending with fresh requestId")
    }

    /**
     * Compute the canonical JSON representation of a flat string map.
     * Keys are sorted alphabetically; values are plain JSON strings.
     * This mirrors OPA's json.marshal() behaviour.
     */
    private fun canonicalJson(entries: Map<String, String>): String {
        val sorted = entries.entries.sortedBy { it.key }
        val body = sorted.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return "{$body}"
    }

    /**
     * Compute SHA-256 fingerprint of a canonical JSON string.
     * Mirrors OPA's: crypto.sha256(json.marshal(parsed_arguments))
     */
    private fun fingerprintOf(canonicalJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun callEvaluate(
        toolName: String,
        callerIdentity: String,
        arguments: String,
        argumentsFingerprint: String,
        sessionId: String = ""
    ): JsonObject {
        val body = buildString {
            append("""{"toolName":""")
            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(toolName)))
            append(""","callerIdentity":""")
            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(callerIdentity)))
            append(""","callerClaims":{},"arguments":""")
            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(arguments)))
            append(""","argumentsFingerprint":""")
            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(argumentsFingerprint)))
            append(""","sessionId":""")
            append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(sessionId)))
            append(""","requestPayload":""}""")
        }

        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/Workflow/$workflowId/evaluate"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertTrue(response.status.isSuccess(),
            "Workflow.evaluate() should succeed: ${response.status} — ${response.bodyAsText()}")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun callAdmin(action: String, body: String) {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/governance/Workflow/$workflowId/$action"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertTrue(response.status.isSuccess(),
            "Workflow.$action should succeed: ${response.status} — ${response.bodyAsText()}")
    }

    private suspend fun ensureWorkflow(serviceName: String): String {
        val listResp = client.get("${TestConfig.nplUrl}/npl/governance/Workflow/") {
            header("Authorization", "Bearer $adminToken")
        }
        if (listResp.status.isSuccess()) {
            val items = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["items"]?.jsonArray
            if (items != null) {
                for (item in items) {
                    if (item.jsonObject["serviceName"]?.jsonPrimitive?.content == serviceName) {
                        return item.jsonObject["@id"]!!.jsonPrimitive.content
                    }
                }
            }
        }

        val createResp = client.post("${TestConfig.nplUrl}/npl/governance/Workflow/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"@parties": {}}""")
        }
        val instanceId = json.parseToJsonElement(createResp.bodyAsText()).jsonObject["@id"]!!
            .jsonPrimitive.content

        val setupResp = client.post(
            "${TestConfig.nplUrl}/npl/governance/Workflow/$instanceId/setup"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"name": "$serviceName"}""")
        }
        check(setupResp.status.isSuccess()) {
            "Workflow.setup() failed: ${setupResp.status} — ${setupResp.bodyAsText()}"
        }
        return instanceId
    }

    @AfterAll
    fun teardown() {
        if (::client.isInitialized) client.close()
    }
}
