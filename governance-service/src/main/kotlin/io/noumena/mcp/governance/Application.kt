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
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * NPL Governance Service — ext_authz backend for Envoy AI Gateway.
 *
 * This slim HTTP service implements the ext_authz contract:
 *   - Envoy forwards authorization checks to GET /auth/check
 *   - Service extracts user identity and requested resource from headers
 *   - Checks NPL ToolPolicy (service-level) and UserToolAccess (user-level)
 *   - Returns 200 (allow) or 403 (deny)
 *
 * Headers from Envoy:
 *   - Authorization: Bearer <JWT> (original user token, for extracting identity)
 *   - X-Forwarded-Uri: /mcp (the original request path)
 *   - X-Forwarded-Method: POST
 *   - X-Envoy-Original-Path: /mcp
 *   - x-mcp-service: <service-name> (set by Envoy MCP route based on tool routing)
 *   - x-mcp-tool: <tool-name> (set by Envoy MCP route)
 *   - x-mcp-user-id: <user-id> (extracted from JWT by Envoy)
 *
 * Response headers (passed upstream by Envoy):
 *   - X-Governance-Allowed: true/false
 *   - X-Governance-Reason: <reason string>
 *   - X-Governance-Service: <service name>
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090

    logger.info { "Starting NPL Governance Service on port $port" }

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
        // Health check
        get("/health") {
            call.respondText(
                """{"status":"healthy","service":"governance-service"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        // ext_authz check endpoint
        // Envoy prepends path_prefix to original path, so we get /auth/check/mcp,
        // /auth/check/health, etc. Match any path under /auth/check.
        route("/auth/check/{...}") {
            handle {
                handleAuthCheck(call, governanceClient)
            }
        }

        // Also match exact /auth/check (no trailing path)
        route("/auth/check") {
            handle {
                handleAuthCheck(call, governanceClient)
            }
        }

        // Cache management
        post("/admin/clear-cache") {
            governanceClient.clearCache()
            call.respondText(
                """{"status":"cache_cleared"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }
}

/**
 * Handle ext_authz check request from Envoy.
 *
 * Extracts service, tool, and user identity from headers set by Envoy,
 * performs NPL policy checks, and returns 200 (allow) or 403 (deny).
 */
private suspend fun handleAuthCheck(call: ApplicationCall, governanceClient: NplGovernanceClient) {
    // Extract identity and resource info from Envoy headers
    val serviceName = call.request.header("x-mcp-service")
    val toolName = call.request.header("x-mcp-tool")
    val userId = call.request.header("x-mcp-user-id")
    val forwardedUri = call.request.header("x-forwarded-uri") ?: call.request.header("x-envoy-original-path")

    logger.info { "ext_authz check: service=$serviceName, tool=$toolName, user=$userId, uri=$forwardedUri" }

    // If no service/tool headers are present, this is likely an initialize or tools/list request
    // which doesn't need governance checks — allow it through
    if (serviceName.isNullOrBlank() || toolName.isNullOrBlank()) {
        logger.info { "No service/tool in request — allowing (non-tool-call request)" }
        call.response.header("X-Governance-Allowed", "true")
        call.response.header("X-Governance-Reason", "Non-tool-call request")
        call.respond(HttpStatusCode.OK, """{"allowed":true,"reason":"Non-tool-call request"}""")
        return
    }

    if (userId.isNullOrBlank()) {
        logger.warn { "No user identity in request — denying (fail-closed)" }
        call.response.header("X-Governance-Allowed", "false")
        call.response.header("X-Governance-Reason", "No user identity")
        call.respond(HttpStatusCode.Forbidden, """{"allowed":false,"reason":"No user identity in request"}""")
        return
    }

    // Perform NPL policy check
    val result = governanceClient.checkPolicy(serviceName, toolName, userId)

    call.response.header("X-Governance-Allowed", result.allowed.toString())
    call.response.header("X-Governance-Reason", result.reason ?: "")
    call.response.header("X-Governance-Service", serviceName)

    if (result.allowed) {
        logger.info { "ALLOWED: $serviceName.$toolName for user $userId" }
        call.respond(
            HttpStatusCode.OK,
            """{"allowed":true,"reason":"${result.reason ?: "Policy checks passed"}"}"""
        )
    } else {
        logger.info { "DENIED: $serviceName.$toolName for user $userId — ${result.reason}" }
        call.respond(
            HttpStatusCode.Forbidden,
            """{"allowed":false,"reason":"${result.reason ?: "Access denied"}"}"""
        )
    }
}
