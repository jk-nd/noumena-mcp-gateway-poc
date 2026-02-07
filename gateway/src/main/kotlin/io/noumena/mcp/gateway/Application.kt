package io.noumena.mcp.gateway

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.utils.io.*
import io.noumena.mcp.gateway.server.McpServerHandler
import io.noumena.mcp.gateway.upstream.UpstreamSessionManager
import io.noumena.mcp.gateway.upstream.UpstreamRouter
import io.noumena.mcp.shared.config.ServicesConfigLoader
import com.auth0.jwk.JwkProviderBuilder
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Serializable
data class HealthResponse(val status: String, val service: String, val mcpEnabled: Boolean = true)

/**
 * SSE session for MCP Inspector connections.
 * Each session has a channel for sending responses back to the client.
 */
data class SseSession(
    val id: String,
    val responseChannel: Channel<String> = Channel(Channel.BUFFERED),
    val createdAt: Long = System.currentTimeMillis()
)

// Active SSE sessions
val sseSessions = ConcurrentHashMap<String, SseSession>()

fun main() {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    
    logger.info { "Starting Noumena MCP Gateway Proxy on $host:$port" }
    
    embeddedServer(Netty, port = port, host = host) {
        configureGateway()
    }.start(wait = true)
}

fun Application.configureGateway() {
    // ─── Plugins ────────────────────────────────────────────────────────────
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CallLogging)
    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("mcp-protocol-version")
        allowCredentials = true
        exposeHeader(HttpHeaders.ContentType)
        exposeHeader(HttpHeaders.WWWAuthenticate)
    }
    
    // ─── Keycloak JWT Configuration ─────────────────────────────────────────
    val keycloakUrl = System.getenv("KEYCLOAK_URL") ?: "http://keycloak:11000"
    val keycloakRealm = System.getenv("KEYCLOAK_REALM") ?: "mcpgateway"
    // Issuer must match the tokens clients actually receive
    val keycloakIssuer = System.getenv("KEYCLOAK_ISSUER") ?: "$keycloakUrl/realms/$keycloakRealm"
    // External URL for browser-facing OAuth redirects (authorization endpoint etc.)
    // Defaults to keycloak:11000 (requires /etc/hosts entry: 127.0.0.1 keycloak)
    val keycloakExternalUrl = System.getenv("KEYCLOAK_EXTERNAL_URL") ?: "http://keycloak:11000"
    val keycloakExternalIssuer = "$keycloakExternalUrl/realms/$keycloakRealm"
    
    // JWKS URL uses Docker-internal hostname (server-side resolution)
    val jwksUrl = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect/certs"
    
    logger.info { "JWT: issuer=$keycloakIssuer, jwks=$jwksUrl, externalIssuer=$keycloakExternalIssuer" }
    
    val jwkProvider = JwkProviderBuilder(URL(jwksUrl))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
    
    install(Authentication) {
        // Standard JWT from Authorization header
        jwt("keycloak") {
            verifier(jwkProvider) {
                withIssuer(keycloakIssuer)
                acceptLeeway(10)
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    """Bearer resource_metadata="/.well-known/oauth-protected-resource""""
                )
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Authentication required. Provide a Bearer token in the Authorization header.")
                )
            }
        }
        
        // JWT from query parameter (for SSE - EventSource can't set headers)
        jwt("keycloak-sse") {
            authHeader { call ->
                // Try Authorization header first, then query parameter
                val headerToken = call.request.parseAuthorizationHeader()
                if (headerToken != null) return@authHeader headerToken
                
                val queryToken = call.request.queryParameters["token"]
                if (queryToken != null) {
                    parseAuthorizationHeader("Bearer $queryToken")
                } else {
                    null
                }
            }
            verifier(jwkProvider) {
                withIssuer(keycloakIssuer)
                acceptLeeway(10)
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    """Bearer resource_metadata="/.well-known/oauth-protected-resource""""
                )
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Authentication required. Pass token as query parameter: /sse?token=YOUR_JWT")
                )
            }
        }
    }
    
    // ─── Upstream infrastructure ─────────────────────────────────────────────
    val upstreamRouter = UpstreamRouter()
    val upstreamSessionManager = UpstreamSessionManager(upstreamRouter)
    
    // Create MCP Server handler (transparent proxy)
    val mcpHandler = McpServerHandler(upstreamRouter = upstreamRouter, upstreamSessionManager = upstreamSessionManager)
    
    routing {
        // Health check (public - no auth required)
        get("/health") {
            call.respond(HealthResponse("ok", "gateway"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // OAuth Discovery Endpoints (MCP Auth Spec)
        // ─────────────────────────────────────────────────────────────────────
        
        get("/.well-known/oauth-protected-resource/{path...}") {
            respondProtectedResourceMetadata(call, keycloakIssuer)
        }
        get("/.well-known/oauth-protected-resource") {
            respondProtectedResourceMetadata(call, keycloakIssuer)
        }
        
        get("/.well-known/oauth-authorization-server/{path...}") {
            respondAuthServerMetadata(call, keycloakIssuer)
        }
        get("/.well-known/oauth-authorization-server") {
            respondAuthServerMetadata(call, keycloakIssuer)
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // OAuth Proxy Endpoints
        // ─────────────────────────────────────────────────────────────────────
        
        get("/authorize") {
            // Use external Keycloak URL — the browser needs to reach Keycloak directly
            val oidcBase = "$keycloakExternalIssuer/protocol/openid-connect"
            val keycloakAuthUrl = "$oidcBase/auth"
            val queryString = call.request.queryString()
            val redirectUrl = if (queryString.isNotEmpty()) {
                "$keycloakAuthUrl?$queryString"
            } else {
                keycloakAuthUrl
            }
            logger.info { "OAuth /authorize → redirecting to Keycloak: $redirectUrl" }
            call.respondRedirect(redirectUrl)
        }
        
        post("/token") {
            val oidcBase = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect"
            val keycloakTokenUrl = "$oidcBase/token"
            val requestBody = call.receiveText()
            logger.info { "OAuth /token → proxying to Keycloak: $keycloakTokenUrl" }
            
            try {
                val formParams = io.ktor.http.Parameters.build {
                    requestBody.split("&").forEach { param ->
                        val parts = param.split("=", limit = 2)
                        if (parts.size == 2) {
                            append(
                                java.net.URLDecoder.decode(parts[0], "UTF-8"),
                                java.net.URLDecoder.decode(parts[1], "UTF-8")
                            )
                        }
                    }
                }
                
                val tokenClient = HttpClient(CIO)
                val keycloakResponse = tokenClient.submitForm(
                    url = keycloakTokenUrl,
                    formParameters = formParams
                )
                
                val responseBody = keycloakResponse.bodyAsText()
                call.respondText(responseBody, ContentType.Application.Json, keycloakResponse.status)
            } catch (e: Exception) {
                logger.error(e) { "Token proxy failed" }
                call.respondText(
                    """{"error": "token_proxy_error", "error_description": "${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadGateway
                )
            }
        }
        
        post("/register") {
            // RFC 7591 Dynamic Client Registration
            // Parse the client's request to echo back their desired redirect_uris
            val requestBody = try {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {}
            }
            
            val clientRedirectUris = requestBody["redirect_uris"]?.jsonArray?.map {
                it.jsonPrimitive.content
            } ?: listOf("http://localhost:6274/oauth/callback")
            
            val response = buildJsonObject {
                put("client_id", System.getenv("KEYCLOAK_CLIENT_ID") ?: "mcpgateway")
                put("client_name", requestBody["client_name"]?.jsonPrimitive?.content ?: "MCP Client")
                put("token_endpoint_auth_method", "none")
                putJsonArray("redirect_uris") {
                    clientRedirectUris.forEach { add(it) }
                }
                putJsonArray("grant_types") {
                    add("authorization_code")
                    add("refresh_token")
                }
                putJsonArray("response_types") {
                    add("code")
                }
            }
            logger.info { "OAuth /register → returning client info (redirect_uris=$clientRedirectUris)" }
            call.respondText(
                Json.encodeToString(JsonObject.serializer(), response),
                ContentType.Application.Json,
                HttpStatusCode.Created
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // Admin endpoints - require JWT with role=admin
        // ─────────────────────────────────────────────────────────────────────
        authenticate("keycloak") {
            get("/admin/services") {
                val principal = call.principal<JWTPrincipal>()!!
                val roles = principal.payload.getClaim("role")?.asList(String::class.java) ?: emptyList()
                if ("admin" !in roles) {
                    call.respondText(
                        """{"error": "Forbidden: admin role required"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                    return@get
                }
                
                val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
                val config = try {
                    ServicesConfigLoader.load(configPath)
                } catch (e: Exception) {
                    logger.error { "Failed to load services config: ${e.message}" }
                    call.respondText(
                        """{"error": "Failed to load config: ${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                    return@get
                }
                
                val response = buildJsonObject {
                    putJsonArray("services") {
                        config.services.forEach { svc ->
                            addJsonObject {
                                put("name", svc.name)
                                put("displayName", svc.displayName)
                                put("type", svc.type)
                                put("enabled", svc.enabled)
                                put("description", svc.description)
                                put("endpoint", svc.endpoint ?: "")
                                put("toolCount", svc.tools.size)
                                putJsonArray("tools") {
                                    svc.tools.forEach { tool ->
                                        addJsonObject {
                                            put("name", "${svc.name}.${tool.name}")
                                            put("description", tool.description)
                                            put("enabled", tool.enabled)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    put("totalServices", config.services.size)
                    put("enabledServices", config.services.count { it.enabled })
                    put("totalTools", config.services.flatMap { it.tools }.size)
                }
                
                call.respondText(
                    Json.encodeToString(JsonObject.serializer(), response),
                    ContentType.Application.Json
                )
            }
            
            post("/admin/services/reload") {
                val principal = call.principal<JWTPrincipal>()!!
                val roles = principal.payload.getClaim("role")?.asList(String::class.java) ?: emptyList()
                if ("admin" !in roles) {
                    call.respondText(
                        """{"error": "Forbidden: admin role required"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                    return@post
                }
                
                logger.info { "Reloading services configuration..." }
                val config = ServicesConfigLoader.reload()
                
                // Clear stale upstream sessions so changed services get fresh connections
                upstreamSessionManager.clearStaleSessions(config)
                logger.info { "Cleared stale upstream sessions after config reload" }
                
                val response = buildJsonObject {
                    put("status", "reloaded")
                    put("servicesLoaded", config.services.size)
                    put("enabledServices", config.services.count { it.enabled })
                }
                
                call.respondText(
                    Json.encodeToString(JsonObject.serializer(), response),
                    ContentType.Application.Json
                )
            }
        } // end admin authenticate block
        
        // ─────────────────────────────────────────────────────────────────────
        // MCP endpoints - require any valid JWT (transparent proxy)
        // ─────────────────────────────────────────────────────────────────────
        authenticate("keycloak") {
        
        // MCP HTTP POST endpoint for agents
        post("/mcp") {
            val requestBody = call.receiveText()
            val principal = call.principal<JWTPrincipal>()!!
            val user = principal.payload.subject ?: "unknown"
            val roles = principal.payload.getClaim("role")?.asList(String::class.java) ?: emptyList()
            
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ MCP REQUEST via HTTP POST                                     ║" }
            logger.info { "║ User: $user  Roles: $roles" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                val response = processMcpMessage(requestBody, mcpHandler, user)
                call.respondText(response, ContentType.Application.Json)
            } catch (e: Exception) {
                logger.error(e) { "HTTP MCP error" }
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", null as String?)
                    putJsonObject("error") {
                        put("code", -32603)
                        put("message", e.message ?: "Internal error")
                    }
                }
                call.respondText(errorResponse.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
        
        // MCP WebSocket endpoint for agents (streaming/realtime)
        webSocket("/mcp/ws") {
            val principal = call.principal<JWTPrincipal>()
            val user = principal?.payload?.subject ?: "unknown"
            
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ AGENT CONNECTED via WebSocket (user: $user)" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            val response = processMcpMessage(text, mcpHandler, user)
                            if (response.isNotEmpty()) {
                                send(Frame.Text(response))
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "WebSocket error" }
            } finally {
                logger.info { "AGENT DISCONNECTED (user: $user)" }
            }
        }
        
        } // end MCP authenticate block
        
        // ─────────────────────────────────────────────────────────────────────
        // SSE endpoint for MCP Inspector - uses keycloak-sse auth
        // ─────────────────────────────────────────────────────────────────────
        authenticate("keycloak-sse") {
            get("/sse") {
                val principal = call.principal<JWTPrincipal>()!!
                val user = principal.payload.subject ?: "unknown"
                val roles = principal.payload.getClaim("role")?.asList(String::class.java) ?: emptyList()
                
                val sessionId = UUID.randomUUID().toString()
                val session = SseSession(id = sessionId)
                sseSessions[sessionId] = session
                
                logger.info { "SSE CLIENT CONNECTED (MCP Inspector) - Session: $sessionId, User: $user, Roles: $roles" }
                
                val host = call.request.host()
                val port = call.request.port()
                val scheme = if (call.request.local.scheme == "https") "https" else "http"
                val messageEndpoint = "$scheme://$host:$port/message?sessionId=$sessionId"
                
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        write("event: endpoint\n")
                        write("data: $messageEndpoint\n\n")
                        flush()
                        
                        logger.info { "SSE: Sent endpoint event: $messageEndpoint" }
                        
                        while (true) {
                            val message = withTimeoutOrNull(30_000) {
                                session.responseChannel.receive()
                            }
                            
                            if (message != null) {
                                val compactMessage = try {
                                    val element = Json.parseToJsonElement(message)
                                    Json.encodeToString(JsonElement.serializer(), element)
                                } catch (e: Exception) {
                                    message.replace("\n", "").replace("\r", "")
                                }
                                write("event: message\n")
                                write("data: $compactMessage\n\n")
                                flush()
                                logger.info { "SSE: Sent message event" }
                            } else {
                                write(": keepalive\n\n")
                                flush()
                            }
                        }
                    } catch (e: Exception) {
                        logger.info { "SSE connection closed: ${e.message}" }
                    } finally {
                        sseSessions.remove(sessionId)
                        session.responseChannel.close()
                        logger.info { "SSE session $sessionId cleaned up" }
                    }
                }
            }
        } // end SSE authenticate block
        
        // Message endpoint for SSE - also requires JWT
        authenticate("keycloak") {
            post("/message") {
                val sessionId = call.request.queryParameters["sessionId"]
                if (sessionId == null) {
                    call.respondText(
                        """{"error": "Missing sessionId parameter"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }
                
                val session = sseSessions[sessionId]
                if (session == null) {
                    call.respondText(
                        """{"error": "Session not found"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                    return@post
                }
                
                val requestBody = call.receiveText()
                val principal = call.principal<JWTPrincipal>()!!
                val user = principal.payload.subject ?: "unknown"
                
                logger.info { "MCP REQUEST via SSE (MCP Inspector) - User: $user" }
                
                try {
                    val response = processMcpMessage(requestBody, mcpHandler, user)
                    
                    if (response.isNotEmpty()) {
                        session.responseChannel.send(response)
                    }
                    
                    call.respondText("Accepted", ContentType.Text.Plain, HttpStatusCode.Accepted)
                } catch (e: Exception) {
                    logger.error(e) { "SSE message processing error" }
                    call.respondText(
                        """{"error": "${e.message}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
        } // end message authenticate block
    }
    
    // Periodic cleanup of stale SSE sessions
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(60_000) // Every minute
            val staleThreshold = System.currentTimeMillis() - 3600_000
            sseSessions.entries.removeIf { (id, session) ->
                if (session.createdAt < staleThreshold) {
                    session.responseChannel.close()
                    logger.info { "Cleaned up stale SSE session: $id" }
                    true
                } else {
                    false
                }
            }
        }
    }
    
    environment.monitor.subscribe(ApplicationStopped) {
        cleanupJob.cancel()
    }
}

/**
 * Respond with RFC 9728 Protected Resource Metadata.
 */
private suspend fun respondProtectedResourceMetadata(
    call: io.ktor.server.application.ApplicationCall,
    keycloakIssuer: String
) {
    val scheme = call.request.local.scheme
    val host = call.request.host()
    val port = call.request.port()
    val resourceUrl = "$scheme://$host:$port"
    
    val metadata = buildJsonObject {
        put("resource", resourceUrl)
        putJsonArray("authorization_servers") {
            add(resourceUrl)
        }
        putJsonArray("bearer_methods_supported") {
            add("header")
            add("query")
        }
    }
    
    call.respondText(
        Json.encodeToString(JsonObject.serializer(), metadata),
        ContentType.Application.Json
    )
}

/**
 * Respond with RFC 8414 Authorization Server Metadata.
 */
private suspend fun respondAuthServerMetadata(
    call: io.ktor.server.application.ApplicationCall,
    keycloakIssuer: String
) {
    val scheme = call.request.local.scheme
    val host = call.request.host()
    val port = call.request.port()
    val gatewayUrl = "$scheme://$host:$port"
    
    val metadata = buildJsonObject {
        put("issuer", gatewayUrl)
        put("authorization_endpoint", "$gatewayUrl/authorize")
        put("token_endpoint", "$gatewayUrl/token")
        put("registration_endpoint", "$gatewayUrl/register")
        putJsonArray("response_types_supported") {
            add("code")
        }
        putJsonArray("grant_types_supported") {
            add("authorization_code")
            add("refresh_token")
        }
        putJsonArray("code_challenge_methods_supported") {
            add("S256")
        }
        putJsonArray("token_endpoint_auth_methods_supported") {
            add("none")
        }
        putJsonArray("scopes_supported") {
            add("openid")
            add("profile")
            add("email")
        }
    }
    
    call.respondText(
        Json.encodeToString(JsonObject.serializer(), metadata),
        ContentType.Application.Json
    )
}

/**
 * Process an MCP JSON-RPC message through the transparent proxy.
 * 
 * Handles:
 * - initialize → return Gateway server info
 * - tools/list → aggregate namespaced tools from all enabled upstream services
 * - tools/call → parse namespace, check NPL policy, forward to upstream
 * - notifications → pass through
 */
private suspend fun processMcpMessage(
    message: String,
    mcpHandler: McpServerHandler,
    userId: String
): String {
    val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    return try {
        val request = json.parseToJsonElement(message).jsonObject
        val method = request["method"]?.jsonPrimitive?.content
        val id = request["id"]
        val params = request["params"]?.jsonObject
        
        logMcpMessage("INCOMING", "Agent → Gateway", method ?: "unknown", message)
        
        val isNotification = id == null || id is JsonNull
        
        val result = when (method) {
            // ─── Notifications (no response) ────────────────────────────
            "notifications/initialized" -> {
                logger.info { "Client sent initialized notification - handshake complete" }
                null
            }
            "notifications/cancelled" -> {
                logger.info { "Client cancelled a request" }
                null
            }
            
            // ─── Initialize ─────────────────────────────────────────────
            "initialize" -> {
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        put("protocolVersion", "2024-11-05")
                        putJsonObject("serverInfo") {
                            put("name", "noumena-mcp-gateway")
                            put("version", "2.0.0")
                        }
                        putJsonObject("capabilities") {
                            putJsonObject("tools") {
                                put("listChanged", true)
                            }
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            // ─── Tools List (aggregate namespaced tools) ────────────────
            "tools/list" -> {
                mcpHandler.handleToolsList(id, userId)
            }
            
            // ─── Tools Call (parse namespace → NPL check → upstream) ────
            "tools/call" -> {
                val toolName = params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}
                
                logger.info { "╔══════════════════════════════════════════════════════════════╗" }
                logger.info { "║ TOOL CALL: $toolName" }
                logger.info { "╠══════════════════════════════════════════════════════════════╣" }
                logger.info { "║ User: $userId" }
                logger.info { "║ Arguments: ${json.encodeToString(JsonObject.serializer(), arguments).take(80)}..." }
                logger.info { "╚══════════════════════════════════════════════════════════════╝" }
                
                mcpHandler.handleToolCall(id ?: JsonNull, toolName, arguments, userId)
            }
            
            else -> {
                if (isNotification) {
                    logger.debug { "Ignoring unknown notification: $method" }
                    null
                } else {
                    val response = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonNull)
                        putJsonObject("error") {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        }
                    }
                    json.encodeToString(JsonObject.serializer(), response)
                }
            }
        }
        
        if (result != null) {
            logMcpMessage("OUTGOING", "Gateway → Agent", method ?: "unknown", result)
        }
        
        result ?: ""
    } catch (e: Exception) {
        logger.error(e) { "Error processing MCP message" }
        val errorResponse = """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: ${e.message}"}}"""
        logMcpMessage("ERROR", "Gateway → Agent", "error", errorResponse)
        errorResponse
    }
}

/**
 * Log MCP messages with nice formatting for visibility.
 */
private fun logMcpMessage(direction: String, flow: String, method: String, message: String) {
    val separator = "─".repeat(60)
    val json = try {
        val element = Json.parseToJsonElement(message)
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
    } catch (e: Exception) {
        message
    }
    
    val color = when (direction) {
        "INCOMING" -> "→"
        "OUTGOING" -> "←"
        "ERROR" -> "✗"
        else -> "•"
    }
    
    logger.info { "" }
    logger.info { "┌$separator┐" }
    logger.info { "│ $color $direction: $flow [$method]" }
    logger.info { "├$separator┤" }
    json.lines().forEach { line ->
        logger.info { "│ $line" }
    }
    logger.info { "└$separator┘" }
}
