package io.noumena.mcp.integration

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Configuration for integration tests.
 * Reads from environment variables, with defaults for local Docker setup.
 */
object TestConfig {
    val nplUrl: String = System.getenv("NPL_URL") ?: "http://localhost:12000"
    val keycloakUrl: String = System.getenv("KEYCLOAK_URL") ?: "http://localhost:11000"
    val gatewayUrl: String = System.getenv("GATEWAY_URL") ?: "http://localhost:8000"
    val realm: String = "mcpgateway"
    val clientId: String = "mcpgateway"

    // Test user credentials (from Keycloak provisioning)
    val testUsername: String = "admin"
    val testPassword: String = "Welcome123"

    // Named users for OPA/NPL tests
    val jarvisUsername = "jarvis"
    val aliceUsername = "alice"
    val bobUsername = "bob"
    val carolUsername = "carol"
    val gatewayUsername = "gateway"
    val defaultPassword = "Welcome123"
}

/**
 * HTTP client configured for integration tests.
 */
object TestClient {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }
}

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
)

/**
 * Keycloak token helper for integration tests.
 */
object KeycloakAuth {

    /**
     * Get an access token from Keycloak using password grant.
     */
    suspend fun getToken(
        username: String = TestConfig.testUsername,
        password: String = TestConfig.testPassword
    ): String {
        val tokenUrl = "${TestConfig.keycloakUrl}/realms/${TestConfig.realm}/protocol/openid-connect/token"

        println("    Getting Keycloak token from: $tokenUrl")

        // Create a fresh client to avoid lifecycle issues between test classes
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(TestClient.json)
            }
        }

        val response = client.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "password")
                append("client_id", TestConfig.clientId)
                append("username", username)
                append("password", password)
            }
        )

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            client.close()
            throw RuntimeException("Failed to get token: ${response.status} - $body")
        }

        val tokenResponse = TestClient.json.decodeFromString<TokenResponse>(response.bodyAsText())
        client.close()
        println("    Token obtained successfully (expires in ${tokenResponse.expires_in}s)")

        return tokenResponse.access_token
    }
}

/**
 * Build a JSON-RPC 2.0 request string.
 */
fun buildJsonRpc(id: Int, method: String, params: String = "{}"): String {
    return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
}

/**
 * Idempotent NPL bootstrap helpers for v4 GatewayStore singleton (find-or-create pattern).
 *
 * GatewayStore holds:
 *   - Catalog: services + tools with open/gated tags
 *   - Access rules: claim-based and identity-based
 *   - Emergency revocation
 */
object NplBootstrap {

    private val json = TestClient.json

    /** Find or create the GatewayStore singleton. Returns the instance ID. */
    suspend fun ensureGatewayStore(adminToken: String): String {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
        try {
            val listResp = client.get("${TestConfig.nplUrl}/npl/store/GatewayStore/") {
                header("Authorization", "Bearer $adminToken")
            }
            if (listResp.status.isSuccess()) {
                val items = json.parseToJsonElement(listResp.bodyAsText()).jsonObject["items"]?.jsonArray
                if (items != null && items.isNotEmpty()) {
                    return items[0].jsonObject["@id"]!!.jsonPrimitive.content
                }
            }
            val createResp = client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"@parties": {}}""")
            }
            return json.parseToJsonElement(createResp.bodyAsText()).jsonObject["@id"]!!.jsonPrimitive.content
        } finally {
            client.close()
        }
    }

    /** Register a service with tools and tags in one go (idempotent). */
    suspend fun registerServiceWithTools(
        storeId: String,
        serviceName: String,
        tools: Map<String, String>,  // toolName -> tag (open/gated)
        adminToken: String
    ) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
        try {
            // Register service (ignore if already exists)
            client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/registerService") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"serviceName": "$serviceName"}""")
            }
            // Enable service
            client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/enableService") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"serviceName": "$serviceName"}""")
            }
            // Register tools with tags
            for ((toolName, tag) in tools) {
                client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/registerTool") {
                    header("Authorization", "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"serviceName": "$serviceName", "toolName": "$toolName", "tag": "$tag"}""")
                }
            }
        } finally {
            client.close()
        }
    }

    /** Add an access rule to GatewayStore. */
    suspend fun addAccessRule(
        storeId: String,
        id: String,
        matchType: String,
        matchClaims: Map<String, String> = emptyMap(),
        matchIdentity: String = "",
        allowServices: List<String>,
        allowTools: List<String>,
        adminToken: String
    ) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
        try {
            val claimsJson = matchClaims.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
            val servicesJson = allowServices.joinToString(",") { "\"$it\"" }
            val toolsJson = allowTools.joinToString(",") { "\"$it\"" }
            client.post("${TestConfig.nplUrl}/npl/store/GatewayStore/$storeId/addAccessRule") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"id":"$id","matchType":"$matchType","matchClaims":{$claimsJson},"matchIdentity":"$matchIdentity","allowServices":[$servicesJson],"allowTools":[$toolsJson]}""")
            }
        } finally {
            client.close()
        }
    }

    /** Find or create a ServiceGovernance instance for a service. Returns the instance ID. */
    suspend fun ensureServiceGovernance(serviceName: String, adminToken: String): String {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
        try {
            val listResp = client.get("${TestConfig.nplUrl}/npl/governance/ServiceGovernance/") {
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
            // Create instance (parameterless constructor)
            val createResp = client.post("${TestConfig.nplUrl}/npl/governance/ServiceGovernance/") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"@parties": {}}""")
            }
            val instanceId = json.parseToJsonElement(createResp.bodyAsText()).jsonObject["@id"]!!.jsonPrimitive.content

            // Call setup() to set serviceName and transition created → active
            val initResp = client.post("${TestConfig.nplUrl}/npl/governance/ServiceGovernance/$instanceId/setup") {
                header("Authorization", "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"name": "$serviceName"}""")
            }
            if (!initResp.status.isSuccess()) {
                throw RuntimeException("ServiceGovernance.setup() failed: ${initResp.status} - ${initResp.bodyAsText()}")
            }

            return instanceId
        } finally {
            client.close()
        }
    }
}

/**
 * Check if the Docker stack is running (NPL Engine).
 */
suspend fun isDockerStackRunning(): Boolean {
    return try {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
        // NPL Engine uses Spring Boot's actuator health endpoint
        val response = client.get("${TestConfig.nplUrl}/actuator/health")
        client.close()
        response.status.isSuccess()
    } catch (e: Exception) {
        println("    Docker stack not running: ${e.message}")
        false
    }
}

/**
 * Check if Gateway is running.
 */
suspend fun isGatewayRunning(): Boolean {
    return try {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
        val response = client.get("${TestConfig.gatewayUrl}/health")
        client.close()
        response.status.isSuccess()
    } catch (e: Exception) {
        println("    Gateway not running: ${e.message}")
        false
    }
}
