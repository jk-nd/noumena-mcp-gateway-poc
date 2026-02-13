package io.noumena.mcp.governance

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.noumena.mcp.shared.config.ServicesConfigLoader
import io.noumena.mcp.shared.config.UserAccess
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * NPL Governance Service — ext_authz backend for Envoy AI Gateway.
 *
 * Two-tier authorization:
 *   1. FAST PATH (sub-ms): Check services.yaml user_access config locally.
 *      No network call. "Does this user have access to this tool?"
 *   2. SLOW PATH (NPL, only for tools/call): Stateful governance checks.
 *      ToolPolicy.checkAccess + UserToolAccess.hasAccess.
 *      Approval workflows, audit trail, dynamic policy.
 *
 * The service parses the JSON-RPC body forwarded by Envoy (with_request_body)
 * to extract: method, tool name, service name. It decodes the JWT from the
 * Authorization header to get the userId.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
    val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"

    logger.info { "Starting NPL Governance Service on port $port" }
    logger.info { "Loading RBAC config from: $configPath" }

    // Pre-load the services config for fast RBAC lookups
    ServicesConfigLoader.load(configPath)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureGovernance()
    }.start(wait = true)
}

fun Application.configureGovernance() {
    val governanceClient = NplGovernanceClient()

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        })
    }

    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled error in governance service" }
            call.respondText(
                text = """{"error": "${cause.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/health") {
            call.respondText(
                """{"status":"healthy","service":"governance-service"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        // ext_authz endpoint — Envoy prepends path_prefix to original path
        route("/auth/check/{...}") {
            handle { handleAuthCheck(call, governanceClient) }
        }
        route("/auth/check") {
            handle { handleAuthCheck(call, governanceClient) }
        }

        // Admin endpoints
        post("/admin/clear-cache") {
            governanceClient.clearCache()
            ServicesConfigLoader.reload()
            call.respondText(
                """{"status":"cache_cleared"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }
}

/**
 * Handle ext_authz check from Envoy.
 *
 * 1. Parse JSON-RPC body to extract method + tool name
 * 2. Decode JWT to get userId
 * 3. Fast path: check services.yaml RBAC (sub-ms, no network)
 * 4. Slow path: call NPL for stateful governance (only for tools/call)
 */
private suspend fun handleAuthCheck(call: ApplicationCall, governanceClient: NplGovernanceClient) {
    // Read the JSON-RPC body forwarded by Envoy (with_request_body)
    val body = call.receiveText()
    val rpc = parseJsonRpc(body)

    // Extract user identity from JWT
    val authHeader = call.request.header("authorization")
    val userId = extractUserIdFromJwt(authHeader)

    logger.info { "ext_authz: method=${rpc.method}, tool=${rpc.toolName}, user=$userId" }

    // Non-tool-call methods (initialize, tools/list, ping, etc.) — allow through
    if (rpc.method != "tools/call") {
        allow(call, "Non-tool-call method: ${rpc.method}")
        return
    }

    // tools/call requires a tool name and user identity
    if (rpc.toolName == null) {
        deny(call, "tools/call missing tool name in params")
        return
    }
    if (userId == null) {
        deny(call, "No user identity in JWT")
        return
    }

    // --- FAST PATH: local RBAC check from services.yaml (sub-ms) ---
    val rbacResult = checkLocalRbac(userId, rpc.toolName)
    if (!rbacResult.allowed) {
        logger.info { "FAST DENY: ${rpc.toolName} for $userId — ${rbacResult.reason}" }
        deny(call, rbacResult.reason, rpc.toolName)
        return
    }
    logger.info { "FAST ALLOW: ${rpc.toolName} for $userId" }

    // --- SLOW PATH: NPL stateful governance (only if fast path passed) ---
    val serviceName = rbacResult.serviceName ?: rpc.toolName
    val toolName = rbacResult.toolName ?: rpc.toolName

    val policyResult = governanceClient.checkPolicy(serviceName, toolName, userId)
    if (!policyResult.allowed) {
        logger.info { "NPL DENY: $serviceName.$toolName for $userId — ${policyResult.reason}" }
        deny(call, policyResult.reason ?: "NPL policy denied", rpc.toolName)
        return
    }

    logger.info { "ALLOWED: $serviceName.$toolName for $userId (fast+NPL)" }
    allow(call, "Authorized", rpc.toolName)
}

// --- JSON-RPC parsing ---

data class JsonRpcRequest(
    val method: String?,
    val toolName: String?,
    val id: JsonElement? = null
)

private fun parseJsonRpc(body: String): JsonRpcRequest {
    if (body.isBlank()) return JsonRpcRequest(method = null, toolName = null)

    return try {
        val json = Json.parseToJsonElement(body).jsonObject
        val method = json["method"]?.jsonPrimitive?.content
        val params = json["params"]?.jsonObject
        val toolName = params?.get("name")?.jsonPrimitive?.content
        val id = json["id"]
        JsonRpcRequest(method = method, toolName = toolName, id = id)
    } catch (e: Exception) {
        logger.debug { "Failed to parse JSON-RPC body: ${e.message}" }
        JsonRpcRequest(method = null, toolName = null)
    }
}

// --- JWT decoding ---

/**
 * Extract userId from JWT in Authorization header.
 * Priority: email → preferred_username → subject
 * (matches the old gateway's extraction logic)
 */
private fun extractUserIdFromJwt(authHeader: String?): String? {
    if (authHeader == null || !authHeader.startsWith("Bearer ", ignoreCase = true)) return null

    return try {
        val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
        val parts = token.split(".")
        if (parts.size < 2) return null

        // Decode JWT payload (part 1, base64url)
        val payload = String(Base64.getUrlDecoder().decode(padBase64(parts[1])))
        val claims = Json.parseToJsonElement(payload).jsonObject

        claims["email"]?.jsonPrimitive?.content
            ?: claims["preferred_username"]?.jsonPrimitive?.content
            ?: claims["sub"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        logger.debug { "Failed to decode JWT: ${e.message}" }
        null
    }
}

private fun padBase64(s: String): String {
    return when (s.length % 4) {
        2 -> "$s=="
        3 -> "$s="
        else -> s
    }
}

// --- Fast RBAC from services.yaml ---

data class RbacResult(
    val allowed: Boolean,
    val reason: String,
    val serviceName: String? = null,
    val toolName: String? = null
)

/**
 * Check local RBAC config (services.yaml user_access section).
 * This is the fast path — no network calls, just config lookup.
 *
 * The tool name may be namespaced ("duckduckgo.search") or bare ("search").
 * We check both patterns.
 */
private fun checkLocalRbac(userId: String, rawToolName: String): RbacResult {
    val config = ServicesConfigLoader.load()
    val userAccess = config.user_access

    // If no user_access config, fall through to NPL (backward compat)
    if (userAccess == null) {
        return RbacResult(allowed = true, reason = "No local RBAC config — deferring to NPL",
            serviceName = rawToolName, toolName = rawToolName)
    }

    // Find user entry
    val user = userAccess.users.find { it.userId == userId }
    if (user == null) {
        // Check default template
        return if (userAccess.default_template.enabled) {
            RbacResult(allowed = true, reason = "Default template allows access",
                serviceName = rawToolName, toolName = rawToolName)
        } else {
            RbacResult(allowed = false, reason = "User '$userId' not found in RBAC config (fail-closed)")
        }
    }

    // Parse namespaced tool: "duckduckgo.search" → service=duckduckgo, tool=search
    val (serviceName, toolName) = parseToolNamespace(rawToolName)

    // Check if user has access to this service+tool
    return checkUserToolAccess(user, serviceName, toolName, rawToolName)
}

/**
 * Parse a potentially namespaced tool name.
 * "duckduckgo.search" → ("duckduckgo", "search")
 * "search" → (null, "search")
 */
private fun parseToolNamespace(rawToolName: String): Pair<String?, String> {
    val dotIndex = rawToolName.indexOf('.')
    return if (dotIndex > 0) {
        rawToolName.substring(0, dotIndex) to rawToolName.substring(dotIndex + 1)
    } else {
        null to rawToolName
    }
}

/**
 * Check if a user has access to a specific service/tool.
 */
private fun checkUserToolAccess(
    user: UserAccess,
    serviceName: String?,
    toolName: String,
    rawToolName: String
): RbacResult {
    if (user.tools.isEmpty()) {
        return RbacResult(allowed = false, reason = "User '${user.userId}' has no tool access configured")
    }

    // If service name is known, check directly
    if (serviceName != null) {
        val allowedTools = user.tools[serviceName]
        if (allowedTools != null) {
            if (allowedTools.contains("*") || allowedTools.contains(toolName)) {
                return RbacResult(allowed = true, reason = "RBAC: access granted",
                    serviceName = serviceName, toolName = toolName)
            }
            return RbacResult(allowed = false,
                reason = "User '${user.userId}' does not have access to $serviceName.$toolName")
        }
        return RbacResult(allowed = false,
            reason = "User '${user.userId}' has no access to service '$serviceName'")
    }

    // No namespace — search across all services for a matching tool name
    for ((svc, allowedTools) in user.tools) {
        if (allowedTools.contains("*") || allowedTools.contains(toolName)) {
            return RbacResult(allowed = true, reason = "RBAC: access granted (matched in $svc)",
                serviceName = svc, toolName = toolName)
        }
    }

    return RbacResult(allowed = false,
        reason = "User '${user.userId}' does not have access to tool '$rawToolName'")
}

// --- Response helpers ---

private suspend fun allow(call: ApplicationCall, reason: String, tool: String? = null) {
    call.response.header("X-Governance-Allowed", "true")
    call.response.header("X-Governance-Reason", reason)
    if (tool != null) call.response.header("X-Governance-Tool", tool)
    call.respondText(
        """{"allowed":true,"reason":"$reason"}""",
        ContentType.Application.Json,
        HttpStatusCode.OK
    )
}

private suspend fun deny(call: ApplicationCall, reason: String, tool: String? = null) {
    call.response.header("X-Governance-Allowed", "false")
    call.response.header("X-Governance-Reason", reason)
    if (tool != null) call.response.header("X-Governance-Tool", tool)
    call.respondText(
        """{"allowed":false,"reason":"$reason"}""",
        ContentType.Application.Json,
        HttpStatusCode.Forbidden
    )
}
