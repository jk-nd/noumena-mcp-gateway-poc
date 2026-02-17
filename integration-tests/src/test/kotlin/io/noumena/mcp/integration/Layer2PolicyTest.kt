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

/**
 * Integration tests for Layer 2 contextual routing protocols.
 *
 * Tests all 5 Layer 2 protocols directly via NPL REST API:
 * - RateLimitPolicy: per-service call limits
 * - ConstraintPolicy: per-caller tool call budgets
 * - PreconditionPolicy: system state flags gating tool calls
 * - FlowPolicy: cross-call data flow governance within sessions
 * - IdentityPolicy: segregation of duties, four-eyes, exclusive actor
 *
 * No gateway, OPA, or MCP transport needed — we call evaluate() with the
 * gateway token the same way OPA calls it at runtime.
 *
 * Prerequisites:
 * - Docker stack running: docker compose -f deployments/docker-compose.yml up -d
 * - NPL Engine healthy with V12 migration applied
 *
 * Run with: ./gradlew :integration-tests:test --tests "*Layer2PolicyTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Layer2PolicyTest {

    private lateinit var adminToken: String
    private lateinit var gatewayToken: String
    private lateinit var client: HttpClient

    private lateinit var rateLimitPolicyId: String
    private lateinit var constraintPolicyId: String
    private lateinit var preconditionPolicyId: String
    private lateinit var flowPolicyId: String
    private lateinit var identityPolicyId: String

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeAll
    fun setup() = runBlocking {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║ LAYER 2 POLICY INTEGRATION TESTS                             ║")
        println("╠════════════════════════════════════════════════════════════════╣")
        println("║ NPL URL:      ${TestConfig.nplUrl}")
        println("║ Keycloak URL: ${TestConfig.keycloakUrl}")
        println("╚════════════════════════════════════════════════════════════════╝")

        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        Assumptions.assumeTrue(isDockerStackRunning()) {
            "Docker stack is not running. Start with: docker compose -f deployments/docker-compose.yml up -d"
        }

        // Get tokens
        adminToken = KeycloakAuth.getToken("admin", "Welcome123")
        println("    ✓ Admin token obtained (pAdmin)")
        gatewayToken = KeycloakAuth.getToken("gateway", "Welcome123")
        println("    ✓ Gateway token obtained (pGateway)")

        // Find-or-create singletons for each protocol
        rateLimitPolicyId = ensureProtocol("RateLimitPolicy")
        println("    ✓ RateLimitPolicy: $rateLimitPolicyId")
        constraintPolicyId = ensureProtocol("ConstraintPolicy")
        println("    ✓ ConstraintPolicy: $constraintPolicyId")
        preconditionPolicyId = ensureProtocol("PreconditionPolicy")
        println("    ✓ PreconditionPolicy: $preconditionPolicyId")
        flowPolicyId = ensureProtocol("FlowPolicy")
        println("    ✓ FlowPolicy: $flowPolicyId")
        identityPolicyId = ensureProtocol("IdentityPolicy")
        println("    ✓ IdentityPolicy: $identityPolicyId")
    }

    // ════════════════════════════════════════════════════════════════════════
    // ConstraintPolicy — "Banking transfer limit"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    fun `CP - set constraint for banking transfer`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ CP-1: Set constraint (banking.transfer max 3)              │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("ConstraintPolicy", constraintPolicyId, "setConstraint",
            """{"serviceName": "banking", "toolName": "transfer", "maxOccurrences": 3, "description": "Max 3 transfers per caller"}""")

        println("    ✓ Constraint set: banking.transfer max 3 per caller")
    }

    @Test
    @Order(2)
    fun `CP - under limit allows calls`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ CP-2: Under limit — 3 calls allowed for alice              │")
        println("└─────────────────────────────────────────────────────────────┘")

        for (i in 1..3) {
            val result = callEvaluate("ConstraintPolicy", constraintPolicyId,
                serviceName = "banking", toolName = "transfer", callerIdentity = "alice@acme.com")
            assertEquals("allow", result, "Call $i/3 should be allowed")
            println("    ✓ Call $i/3: allow")
        }
    }

    @Test
    @Order(3)
    fun `CP - at limit denies call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ CP-3: At limit — 4th call denied for alice                 │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("ConstraintPolicy", constraintPolicyId,
            serviceName = "banking", toolName = "transfer", callerIdentity = "alice@acme.com")
        assertEquals("deny", result, "4th call should be denied (limit reached)")
        println("    ✓ Call 4/3: deny (limit reached)")
    }

    @Test
    @Order(4)
    fun `CP - separate caller has own counter`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ CP-4: Separate caller — bob has own counter                │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("ConstraintPolicy", constraintPolicyId,
            serviceName = "banking", toolName = "transfer", callerIdentity = "bob@acme.com")
        assertEquals("allow", result, "Bob's first call should be allowed (own counter)")
        println("    ✓ bob@acme.com: allow (own counter, 1/3)")
    }

    @Test
    @Order(5)
    fun `CP - reset counter allows again`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ CP-5: Reset counter — alice can call again                 │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("ConstraintPolicy", constraintPolicyId, "resetCounter",
            """{"callerIdentity": "alice@acme.com", "serviceName": "banking", "toolName": "transfer"}""")
        println("    ✓ Counter reset for alice")

        val result = callEvaluate("ConstraintPolicy", constraintPolicyId,
            serviceName = "banking", toolName = "transfer", callerIdentity = "alice@acme.com")
        assertEquals("allow", result, "Alice should be allowed after counter reset")
        println("    ✓ alice@acme.com: allow (counter reset)")
    }

    // ════════════════════════════════════════════════════════════════════════
    // PreconditionPolicy — "Trading hours gate"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    fun `PP - set condition and rule`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ PP-1: Set condition + rule (market_status=open)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("PreconditionPolicy", preconditionPolicyId, "setCondition",
            """{"conditionName": "market_status", "value": "open"}""")
        println("    ✓ Condition set: market_status = open")

        callAdmin("PreconditionPolicy", preconditionPolicyId, "addRule",
            """{"conditionName": "market_status", "requiredValue": "open", "serviceName": "trading", "toolName": "execute_trade"}""")
        println("    ✓ Rule added: trading.execute_trade requires market_status == open")
    }

    @Test
    @Order(11)
    fun `PP - condition met allows call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ PP-2: Condition met — trade allowed                        │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("PreconditionPolicy", preconditionPolicyId,
            serviceName = "trading", toolName = "execute_trade")
        assertEquals("allow", result, "Trade should be allowed when market is open")
        println("    ✓ execute_trade: allow (market_status=open)")
    }

    @Test
    @Order(12)
    fun `PP - condition unmet denies call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ PP-3: Condition unmet — trade denied                       │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("PreconditionPolicy", preconditionPolicyId, "setCondition",
            """{"conditionName": "market_status", "value": "closed"}""")
        println("    ✓ Condition updated: market_status = closed")

        val result = callEvaluate("PreconditionPolicy", preconditionPolicyId,
            serviceName = "trading", toolName = "execute_trade")
        assertEquals("deny", result, "Trade should be denied when market is closed")
        println("    ✓ execute_trade: deny (market_status=closed)")
    }

    @Test
    @Order(13)
    fun `PP - condition missing denies call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ PP-4: Condition missing — trade denied                     │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("PreconditionPolicy", preconditionPolicyId, "removeCondition",
            """{"conditionName": "market_status"}""")
        println("    ✓ Condition removed: market_status")

        val result = callEvaluate("PreconditionPolicy", preconditionPolicyId,
            serviceName = "trading", toolName = "execute_trade")
        assertEquals("deny", result, "Trade should be denied when condition is missing")
        println("    ✓ execute_trade: deny (condition missing)")
    }

    @Test
    @Order(14)
    fun `PP - no matching rule allows call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ PP-5: No matching rule — other tool allowed                │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("PreconditionPolicy", preconditionPolicyId,
            serviceName = "trading", toolName = "get_portfolio")
        assertEquals("allow", result, "Unconstrained tool should be allowed")
        println("    ✓ get_portfolio: allow (no precondition rule)")
    }

    // ════════════════════════════════════════════════════════════════════════
    // FlowPolicy — "PII exfiltration prevention"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    fun `FP - set flow rule`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ FP-1: Set flow rule (crm.get_customer -> email.send)       │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("FlowPolicy", flowPolicyId, "setFlowRule",
            """{"sourceService": "crm", "sourceTool": "get_customer", "targetService": "email", "targetTool": "send_email", "description": "Block PII exfiltration"}""")
        println("    ✓ Flow rule: crm.get_customer -> email.send_email = deny")
    }

    @Test
    @Order(21)
    fun `FP - clean session allows target`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ FP-2: Clean session — send_email allowed                   │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("FlowPolicy", flowPolicyId,
            serviceName = "email", toolName = "send_email", sessionId = "session-A")
        assertEquals("allow", result, "send_email should be allowed in clean session")
        println("    ✓ email.send_email (session-A): allow (no prior crm call)")
    }

    @Test
    @Order(22)
    fun `FP - source recorded in session`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ FP-3: Source recorded — get_customer allowed               │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("FlowPolicy", flowPolicyId,
            serviceName = "crm", toolName = "get_customer", sessionId = "session-B")
        assertEquals("allow", result, "get_customer should always be allowed (source, not target)")
        println("    ✓ crm.get_customer (session-B): allow (recorded in history)")
    }

    @Test
    @Order(23)
    fun `FP - flow violation denies target after source`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ FP-4: Flow violation — send_email denied after get_customer│")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("FlowPolicy", flowPolicyId,
            serviceName = "email", toolName = "send_email", sessionId = "session-B")
        assertEquals("deny", result, "send_email should be denied after get_customer in same session")
        println("    ✓ email.send_email (session-B): deny (crm.get_customer was called)")
    }

    @Test
    @Order(24)
    fun `FP - different session is safe`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ FP-5: Different session — send_email allowed               │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("FlowPolicy", flowPolicyId,
            serviceName = "email", toolName = "send_email", sessionId = "session-C")
        assertEquals("allow", result, "send_email should be allowed in different session")
        println("    ✓ email.send_email (session-C): allow (no crm call in this session)")
    }

    // ════════════════════════════════════════════════════════════════════════
    // IdentityPolicy — "Segregation of duties + four-eyes"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    fun `IP - add segregation of duties rule`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-1: Add SoD rule (banking.payment submit/approve)        │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("IdentityPolicy", identityPolicyId, "addIdentityRule",
            """{"serviceName": "banking", "toolName": "payment", "ruleType": "segregation_of_duties", "primaryVerb": "submit", "secondaryVerb": "approve"}""")
        println("    ✓ SoD rule: banking.payment submit -> approve (same actor denied)")
    }

    @Test
    @Order(31)
    fun `IP - primary action allowed`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-2: Primary action — alice submits payment               │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "payment",
            verb = "submit", callerIdentity = "alice@acme.com", argumentDigest = "entity-1")
        assertEquals("allow", result, "Submit should be allowed (primary action)")
        println("    ✓ alice submit entity-1: allow")
    }

    @Test
    @Order(32)
    fun `IP - SoD violation denies same actor`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-3: SoD violation — alice cannot approve own submission  │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "payment",
            verb = "approve", callerIdentity = "alice@acme.com", argumentDigest = "entity-1")
        assertEquals("deny", result, "Alice should not approve her own submission (SoD)")
        println("    ✓ alice approve entity-1: deny (segregation_of_duties)")
    }

    @Test
    @Order(33)
    fun `IP - different actor can approve`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-4: Different actor — bob approves alice's submission    │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "payment",
            verb = "approve", callerIdentity = "bob@acme.com", argumentDigest = "entity-1")
        assertEquals("allow", result, "Bob should be able to approve alice's submission")
        println("    ✓ bob approve entity-1: allow (different actor)")
    }

    @Test
    @Order(34)
    fun `IP - add four-eyes rule`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-5: Add four-eyes rule (banking.transfer review/finalize)│")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("IdentityPolicy", identityPolicyId, "addIdentityRule",
            """{"serviceName": "banking", "toolName": "transfer", "ruleType": "four_eyes", "primaryVerb": "review", "secondaryVerb": "finalize"}""")
        println("    ✓ Four-eyes rule: banking.transfer review -> finalize (need 2 reviewers)")
    }

    @Test
    @Order(35)
    fun `IP - four-eyes single reviewer denied`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-6: Four-eyes — single reviewer cannot finalize          │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Alice reviews
        val reviewResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "transfer",
            verb = "review", callerIdentity = "alice@acme.com", argumentDigest = "entity-2")
        assertEquals("allow", reviewResult, "Review should be allowed")
        println("    ✓ alice review entity-2: allow")

        // Alice tries to finalize (only she reviewed — four-eyes requires another reviewer)
        val finalizeResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "transfer",
            verb = "finalize", callerIdentity = "alice@acme.com", argumentDigest = "entity-2")
        assertEquals("deny", finalizeResult, "Finalize should be denied (need a second reviewer)")
        println("    ✓ alice finalize entity-2: deny (four_eyes — need second reviewer)")
    }

    @Test
    @Order(36)
    fun `IP - four-eyes two reviewers allowed`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-7: Four-eyes — two reviewers allows finalize            │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Bob also reviews
        val reviewResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "transfer",
            verb = "review", callerIdentity = "bob@acme.com", argumentDigest = "entity-2")
        assertEquals("allow", reviewResult, "Bob's review should be allowed")
        println("    ✓ bob review entity-2: allow")

        // Alice finalizes (bob reviewed, so four-eyes is satisfied)
        val finalizeResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "banking", toolName = "transfer",
            verb = "finalize", callerIdentity = "alice@acme.com", argumentDigest = "entity-2")
        assertEquals("allow", finalizeResult, "Finalize should be allowed (bob also reviewed)")
        println("    ✓ alice finalize entity-2: allow (four_eyes satisfied — bob reviewed)")
    }

    // ── IdentityPolicy — "Exclusive actor" ──────────────────────────────────

    @Test
    @Order(37)
    fun `IP - add exclusive actor rule`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-8: Add exclusive_actor rule (docs.wiki create/update)   │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("IdentityPolicy", identityPolicyId, "addIdentityRule",
            """{"serviceName": "docs", "toolName": "wiki", "ruleType": "exclusive_actor", "primaryVerb": "create", "secondaryVerb": "update"}""")
        println("    ✓ Exclusive actor rule: docs.wiki create -> update (only creator can update)")
    }

    @Test
    @Order(38)
    fun `IP - exclusive actor creator can update`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-9: Exclusive actor — creator can update own entity      │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Alice creates the wiki page
        val createResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "docs", toolName = "wiki",
            verb = "create", callerIdentity = "alice@acme.com", argumentDigest = "wiki-page-1")
        assertEquals("allow", createResult, "Create should be allowed")
        println("    ✓ alice create wiki-page-1: allow")

        // Alice updates her own page
        val updateResult = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "docs", toolName = "wiki",
            verb = "update", callerIdentity = "alice@acme.com", argumentDigest = "wiki-page-1")
        assertEquals("allow", updateResult, "Creator should be able to update own entity")
        println("    ✓ alice update wiki-page-1: allow (exclusive_actor — alice is creator)")
    }

    @Test
    @Order(39)
    fun `IP - exclusive actor non-creator denied`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ IP-10: Exclusive actor — non-creator cannot update         │")
        println("└─────────────────────────────────────────────────────────────┘")

        // Bob tries to update alice's page
        val result = callEvaluate("IdentityPolicy", identityPolicyId,
            serviceName = "docs", toolName = "wiki",
            verb = "update", callerIdentity = "bob@acme.com", argumentDigest = "wiki-page-1")
        assertEquals("deny", result, "Non-creator should be denied (exclusive_actor)")
        println("    ✓ bob update wiki-page-1: deny (exclusive_actor — alice is creator)")
    }

    // ════════════════════════════════════════════════════════════════════════
    // RateLimitPolicy — "Search API throttle"
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    fun `RL - set rate limit`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-1: Set rate limit (search max 5 per-session)            │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("RateLimitPolicy", rateLimitPolicyId, "setLimit",
            """{"serviceName": "search", "maxCalls": 5, "windowLabel": "per-session"}""")
        println("    ✓ Rate limit set: search max 5 per-session")
    }

    @Test
    @Order(41)
    fun `RL - under limit allows calls`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-2: Under limit — 5 calls allowed for alice              │")
        println("└─────────────────────────────────────────────────────────────┘")

        for (i in 1..5) {
            val result = callEvaluate("RateLimitPolicy", rateLimitPolicyId,
                serviceName = "search", toolName = "web_search", callerIdentity = "alice@acme.com")
            assertEquals("allow", result, "Call $i/5 should be allowed")
            println("    ✓ Call $i/5: allow")
        }
    }

    @Test
    @Order(42)
    fun `RL - at limit denies call`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-3: At limit — 6th call denied for alice                 │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("RateLimitPolicy", rateLimitPolicyId,
            serviceName = "search", toolName = "web_search", callerIdentity = "alice@acme.com")
        assertEquals("deny", result, "6th call should be denied (limit reached)")
        println("    ✓ Call 6/5: deny (limit reached)")
    }

    @Test
    @Order(43)
    fun `RL - separate caller has own counter`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-4: Separate caller — bob has own counter                │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("RateLimitPolicy", rateLimitPolicyId,
            serviceName = "search", toolName = "web_search", callerIdentity = "bob@acme.com")
        assertEquals("allow", result, "Bob's first call should be allowed (own counter)")
        println("    ✓ bob@acme.com: allow (own counter, 1/5)")
    }

    @Test
    @Order(44)
    fun `RL - reset usage allows again`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-5: Reset usage — alice can call again                   │")
        println("└─────────────────────────────────────────────────────────────┘")

        callAdmin("RateLimitPolicy", rateLimitPolicyId, "resetUsage",
            """{"callerIdentity": "alice@acme.com", "serviceName": "search"}""")
        println("    ✓ Usage reset for alice on search")

        val result = callEvaluate("RateLimitPolicy", rateLimitPolicyId,
            serviceName = "search", toolName = "web_search", callerIdentity = "alice@acme.com")
        assertEquals("allow", result, "Alice should be allowed after usage reset")
        println("    ✓ alice@acme.com: allow (usage reset)")
    }

    @Test
    @Order(45)
    fun `RL - no limit configured allows unlimited`() = runBlocking {
        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│ RL-6: No limit configured — unlimited calls allowed        │")
        println("└─────────────────────────────────────────────────────────────┘")

        val result = callEvaluate("RateLimitPolicy", rateLimitPolicyId,
            serviceName = "unconfigured-service", toolName = "any_tool", callerIdentity = "alice@acme.com")
        assertEquals("allow", result, "Service with no limit should be unlimited")
        println("    ✓ unconfigured-service: allow (no limit = unlimited)")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cleanup
    // ════════════════════════════════════════════════════════════════════════

    @AfterAll
    fun teardown() = runBlocking {
        println("\n╔════════════════════════════════════════════════════════════════╗")
        println("║ LAYER 2 POLICY TESTS - Cleanup                               ║")
        println("╠════════════════════════════════════════════════════════════════╣")

        if (::client.isInitialized && ::adminToken.isInitialized) {
            try {
                if (::rateLimitPolicyId.isInitialized) {
                    callAdminSafe("RateLimitPolicy", rateLimitPolicyId, "resetAllUsage", "{}")
                    callAdminSafe("RateLimitPolicy", rateLimitPolicyId, "removeLimit",
                        """{"serviceName": "search"}""")
                    println("║ ✓ RateLimitPolicy cleaned up                                 ║")
                }
            } catch (e: Exception) {
                println("║ ⚠ RateLimitPolicy cleanup: ${e.message?.take(40)}")
            }

            try {
                if (::constraintPolicyId.isInitialized) {
                    callAdminSafe("ConstraintPolicy", constraintPolicyId, "resetAllCounters", "{}")
                    callAdminSafe("ConstraintPolicy", constraintPolicyId, "removeConstraint",
                        """{"serviceName": "banking", "toolName": "transfer"}""")
                    println("║ ✓ ConstraintPolicy cleaned up                                ║")
                }
            } catch (e: Exception) {
                println("║ ⚠ ConstraintPolicy cleanup: ${e.message?.take(40)}")
            }

            try {
                if (::preconditionPolicyId.isInitialized) {
                    callAdminSafe("PreconditionPolicy", preconditionPolicyId, "removeRule",
                        """{"conditionName": "market_status", "serviceName": "trading", "toolName": "execute_trade"}""")
                    println("║ ✓ PreconditionPolicy cleaned up                              ║")
                }
            } catch (e: Exception) {
                println("║ ⚠ PreconditionPolicy cleanup: ${e.message?.take(40)}")
            }

            try {
                if (::flowPolicyId.isInitialized) {
                    callAdminSafe("FlowPolicy", flowPolicyId, "clearAllHistory", "{}")
                    callAdminSafe("FlowPolicy", flowPolicyId, "removeFlowRule",
                        """{"sourceService": "crm", "sourceTool": "get_customer", "targetService": "email", "targetTool": "send_email"}""")
                    println("║ ✓ FlowPolicy cleaned up                                      ║")
                }
            } catch (e: Exception) {
                println("║ ⚠ FlowPolicy cleanup: ${e.message?.take(40)}")
            }

            try {
                if (::identityPolicyId.isInitialized) {
                    callAdminSafe("IdentityPolicy", identityPolicyId, "clearAllHistory", "{}")
                    callAdminSafe("IdentityPolicy", identityPolicyId, "removeIdentityRule",
                        """{"serviceName": "banking", "toolName": "payment", "ruleType": "segregation_of_duties"}""")
                    callAdminSafe("IdentityPolicy", identityPolicyId, "removeIdentityRule",
                        """{"serviceName": "banking", "toolName": "transfer", "ruleType": "four_eyes"}""")
                    callAdminSafe("IdentityPolicy", identityPolicyId, "removeIdentityRule",
                        """{"serviceName": "docs", "toolName": "wiki", "ruleType": "exclusive_actor"}""")
                    println("║ ✓ IdentityPolicy cleaned up                                  ║")
                }
            } catch (e: Exception) {
                println("║ ⚠ IdentityPolicy cleanup: ${e.message?.take(40)}")
            }
        }

        println("╚════════════════════════════════════════════════════════════════╝")
        if (::client.isInitialized) {
            client.close()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Find or create a singleton instance of a Layer 2 protocol. */
    private suspend fun ensureProtocol(protocolName: String): String {
        val listResp = client.get("${TestConfig.nplUrl}/npl/policies/$protocolName/") {
            header("Authorization", "Bearer $adminToken")
        }
        if (listResp.status.isSuccess()) {
            val items = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["items"]?.jsonArray
            if (items != null && items.isNotEmpty()) {
                return items[0].jsonObject["@id"]!!.jsonPrimitive.content
            }
        }
        val createResp = client.post("${TestConfig.nplUrl}/npl/policies/$protocolName/") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"@parties": {}}""")
        }
        return json.parseToJsonElement(createResp.bodyAsText()).jsonObject["@id"]!!.jsonPrimitive.content
    }

    /**
     * Call evaluate() on a Layer 2 protocol using the gateway token.
     * Returns the NPL return value: "allow" or "deny".
     */
    private suspend fun callEvaluate(
        protocol: String,
        instanceId: String,
        serviceName: String,
        toolName: String,
        callerIdentity: String = "test@acme.com",
        sessionId: String = "",
        verb: String = "execute",
        labels: String = "",
        annotations: String = "",
        argumentDigest: String = "",
        approvers: List<String> = emptyList(),
        requestPayload: String = ""
    ): String {
        val approversJson = approvers.joinToString(",") { "\"$it\"" }
        val body = """
        {
            "toolName": "$toolName",
            "callerIdentity": "$callerIdentity",
            "sessionId": "$sessionId",
            "verb": "$verb",
            "labels": "$labels",
            "annotations": "$annotations",
            "argumentDigest": "$argumentDigest",
            "approvers": [$approversJson],
            "requestPayload": "$requestPayload",
            "serviceName": "$serviceName"
        }
        """.trimIndent()

        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/$protocol/$instanceId/evaluate"
        ) {
            header("Authorization", "Bearer $gatewayToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertTrue(response.status.isSuccess(),
            "evaluate() on $protocol should succeed: ${response.status} - ${response.bodyAsText()}")
        return response.bodyAsText().trim().removeSurrounding("\"")
    }

    /** Call an admin action on a protocol (asserts success). */
    private suspend fun callAdmin(
        protocol: String, instanceId: String, action: String, body: String
    ) {
        val response = client.post(
            "${TestConfig.nplUrl}/npl/policies/$protocol/$instanceId/$action"
        ) {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertTrue(response.status.isSuccess(),
            "$protocol.$action should succeed: ${response.status} - ${response.bodyAsText()}")
    }

    /** Call an admin action but ignore failures (for cleanup). */
    private suspend fun callAdminSafe(
        protocol: String, instanceId: String, action: String, body: String
    ) {
        try {
            client.post(
                "${TestConfig.nplUrl}/npl/policies/$protocol/$instanceId/$action"
            ) {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            println("    ⚠ $protocol.$action failed: ${e.message?.take(60)}")
        }
    }
}
