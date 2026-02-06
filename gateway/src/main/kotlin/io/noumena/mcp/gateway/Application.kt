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
import io.noumena.mcp.gateway.callback.callbackRoutes
import io.noumena.mcp.gateway.context.contextRoutes
import io.noumena.mcp.gateway.context.ContextStore
import io.noumena.mcp.gateway.messaging.ExecutorPublisher
import io.noumena.mcp.shared.config.ServicesConfigLoader
import io.noumena.mcp.shared.config.ToolDefinition
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
    val responseChannel: Channel<String> = Channel(Channel.UNLIMITED),
    val createdAt: Long = System.currentTimeMillis()
)

// Active SSE sessions (sessionId -> SseSession)
private val sseSessions = ConcurrentHashMap<String, SseSession>()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    
    logger.info { "Starting Noumena MCP Gateway on port $port" }
    logger.info { "NOTE: Gateway has NO Vault access - this is by design" }
    logger.info { "MCP endpoints:" }
    logger.info { "  POST /mcp     - HTTP (LangChain, ADK, Sligo.ai, etc.)" }
    logger.info { "  WS   /mcp/ws  - WebSocket (streaming agents)" }
    logger.info { "  GET  /sse     - SSE (MCP Inspector)" }
    logger.info { "OAuth endpoints:" }
    logger.info { "  GET  /.well-known/oauth-protected-resource      - RFC 9728 (discovery)" }
    logger.info { "  GET  /.well-known/oauth-authorization-server    - RFC 8414 (metadata)" }
    logger.info { "  GET  /authorize                                 - OAuth proxy → Keycloak" }
    logger.info { "  POST /token                                     - OAuth proxy → Keycloak" }
    logger.info { "  POST /register                                  - Dynamic client registration (stub)" }
    
    embeddedServer(Netty, port = port) {
        configureGateway()
    }.start(wait = true)
}

fun Application.configureGateway() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
    
    install(CallLogging)
    
    // CORS for MCP Inspector and other cross-origin clients
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("mcp-protocol-version")  // MCP Inspector sends this in discovery requests
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }
    
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    
    // ─────────────────────────────────────────────────────────────────────
    // JWT Authentication via Keycloak
    //
    // KEYCLOAK_ISSUER = the `iss` claim in tokens (what clients see: localhost)
    // KEYCLOAK_URL    = where this container can actually reach Keycloak (Docker DNS)
    // The JWKS keys are the same regardless of which URL is used.
    // ─────────────────────────────────────────────────────────────────────
    val keycloakUrl = System.getenv("KEYCLOAK_URL") ?: "http://keycloak:11000"
    val keycloakRealm = System.getenv("KEYCLOAK_REALM") ?: "mcpgateway"
    val keycloakIssuer = System.getenv("KEYCLOAK_ISSUER")
        ?: "$keycloakUrl/realms/$keycloakRealm"
    // Fetch JWKS from Docker-internal URL (reachable from this container)
    val jwksUrl = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect/certs"
    
    logger.info { "JWT Auth: issuer=$keycloakIssuer (what tokens carry)" }
    logger.info { "JWT Auth: JWKS URL=$jwksUrl (where we fetch keys)" }
    
    val jwkProvider = JwkProviderBuilder(URL(jwksUrl))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
    
    install(Authentication) {
        jwt("keycloak") {
            this.realm = keycloakRealm
            verifier(jwkProvider, keycloakIssuer) {
                acceptLeeway(3)
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Authentication required. Provide a valid Bearer token from Keycloak realm '$keycloakRealm'.")
                )
            }
        }
        
        // SSE variant: accepts token via query parameter (EventSource can't set headers)
        jwt("keycloak-sse") {
            this.realm = keycloakRealm
            verifier(jwkProvider, keycloakIssuer) {
                acceptLeeway(3)
            }
            authHeader { call ->
                // Try Authorization header first, then fall back to query parameter
                val headerValue = call.request.headers[HttpHeaders.Authorization]
                if (headerValue != null) {
                    try { parseAuthorizationHeader(headerValue) } catch (_: Exception) { null }
                } else {
                    val queryToken = call.request.queryParameters["token"]
                    if (queryToken != null) {
                        io.ktor.http.auth.HttpAuthHeader.Single("Bearer", queryToken)
                    } else {
                        null
                    }
                }
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Authentication required. Pass token as query parameter: /sse?token=YOUR_JWT")
                )
            }
        }
    }
    
    // Initialize RabbitMQ publisher for triggering Executor
    ExecutorPublisher.init()
    
    // Create MCP Server handler
    val mcpHandler = McpServerHandler()
    val mcpServer = mcpHandler.createServer()
    
    routing {
        // Health check (public - no auth required)
        get("/health") {
            call.respond(HealthResponse("ok", "gateway"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // OAuth Discovery Endpoints (MCP Auth Spec)
        //
        // MCP Inspector uses these to discover Keycloak's OAuth endpoints
        // and perform Authorization Code + PKCE flow automatically.
        // No auth required - these are discovery endpoints.
        //
        // Flow:
        // 1. Inspector fetches /.well-known/oauth-protected-resource (RFC 9728)
        //    → learns the authorization server is Keycloak
        // 2. Inspector fetches Keycloak's own /.well-known/openid-configuration
        //    → gets auth/token endpoints natively from Keycloak
        // 3. Fallback: /.well-known/oauth-authorization-server (RFC 8414)
        //    → Gateway provides the same info directly
        // ─────────────────────────────────────────────────────────────────────
        
        // RFC 9728 - Protected Resource Metadata (primary discovery)
        // Handles both /.well-known/oauth-protected-resource and
        // /.well-known/oauth-protected-resource/{path} (e.g., /sse)
        // per RFC 9728 path construction rules.
        get("/.well-known/oauth-protected-resource/{path...}") {
            respondProtectedResourceMetadata(call, keycloakIssuer)
        }
        get("/.well-known/oauth-protected-resource") {
            respondProtectedResourceMetadata(call, keycloakIssuer)
        }
        
        // RFC 8414 - Authorization Server Metadata (fallback discovery)
        // Handles both /.well-known/oauth-authorization-server and
        // /.well-known/oauth-authorization-server/{path} variants.
        get("/.well-known/oauth-authorization-server/{path...}") {
            respondAuthServerMetadata(call, keycloakIssuer)
        }
        get("/.well-known/oauth-authorization-server") {
            respondAuthServerMetadata(call, keycloakIssuer)
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // OAuth Proxy Endpoints
        //
        // The Gateway acts as an OAuth facade so MCP Inspector only talks to
        // one origin (no cross-origin issues with Keycloak).
        // /authorize → redirects browser to Keycloak login
        // /token     → proxies token exchange to Keycloak
        // /register  → returns pre-configured client info (no dynamic registration)
        // ─────────────────────────────────────────────────────────────────────
        
        // OAuth: Redirect to Keycloak authorization endpoint
        get("/authorize") {
            val oidcBase = "$keycloakIssuer/protocol/openid-connect"
            val keycloakAuthUrl = "$oidcBase/auth"
            
            // Forward all query parameters to Keycloak
            val queryString = call.request.queryString()
            val redirectUrl = if (queryString.isNotEmpty()) {
                "$keycloakAuthUrl?$queryString"
            } else {
                keycloakAuthUrl
            }
            
            logger.info { "OAuth /authorize → redirecting to Keycloak: $redirectUrl" }
            call.respondRedirect(redirectUrl)
        }
        
        // OAuth: Proxy token exchange to Keycloak
        // Uses keycloakUrl (Docker-internal) since this is a server-side call,
        // unlike /authorize which is a browser redirect using keycloakIssuer.
        post("/token") {
            val oidcBase = "$keycloakUrl/realms/$keycloakRealm/protocol/openid-connect"
            val keycloakTokenUrl = "$oidcBase/token"
            
            val requestBody = call.receiveText()
            
            logger.info { "OAuth /token → proxying to Keycloak: $keycloakTokenUrl" }
            
            try {
                // Parse incoming form body and forward all parameters to Keycloak
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
                val statusCode = HttpStatusCode.fromValue(keycloakResponse.status.value)
                
                // Forward Keycloak's response headers for CORS
                call.respondText(
                    responseBody,
                    ContentType.Application.Json,
                    statusCode
                )
                tokenClient.close()
            } catch (e: Exception) {
                logger.error(e) { "Token proxy failed" }
                call.respondText(
                    """{"error": "token_proxy_error", "error_description": "${e.message}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadGateway
                )
            }
        }
        
        // OAuth: Dynamic Client Registration (stub - returns pre-configured client)
        post("/register") {
            val response = buildJsonObject {
                put("client_id", System.getenv("KEYCLOAK_CLIENT_ID") ?: "mcpgateway")
                put("client_name", "MCP Gateway")
                put("token_endpoint_auth_method", "none")
                putJsonArray("redirect_uris") {
                    add("http://localhost:6274/oauth/callback")
                }
                putJsonArray("grant_types") {
                    add("authorization_code")
                    add("refresh_token")
                }
                putJsonArray("response_types") {
                    add("code")
                }
            }
            
            logger.info { "OAuth /register → returning pre-configured client info" }
            
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
                // Check admin role
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
                                put("toolCount", svc.tools.size)
                                putJsonArray("tools") {
                                    svc.tools.forEach { tool ->
                                        addJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
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
        // MCP endpoints - require any valid JWT
        // ─────────────────────────────────────────────────────────────────────
        authenticate("keycloak") {
        
        // MCP HTTP POST endpoint for agents (LangChain, ADK, Sligo.ai, etc.)
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
                val response = processMcpMessage(mcpServer, requestBody, mcpHandler)
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
            logger.info { "╔════════════════════════════════════════════════════════════════╗" }
            logger.info { "║ 🔌 AGENT CONNECTED via WebSocket                               ║" }
            logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            
                            // Process through MCP server with verbose logging
                            val response = processMcpMessage(mcpServer, text, mcpHandler)
                            send(Frame.Text(response))
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "WebSocket error" }
            } finally {
                logger.info { "╔════════════════════════════════════════════════════════════════╗" }
                logger.info { "║ 🔌 AGENT DISCONNECTED                                          ║" }
                logger.info { "╚════════════════════════════════════════════════════════════════╝" }
            }
        }
        
        } // end MCP authenticate block
        
        // ─────────────────────────────────────────────────────────────────────
        // SSE endpoint for MCP Inspector - uses keycloak-sse auth
        // (EventSource API cannot set Authorization headers, so we accept
        //  the token via query parameter: /sse?token=YOUR_JWT)
        // ─────────────────────────────────────────────────────────────────────
        authenticate("keycloak-sse") {
            get("/sse") {
                val principal = call.principal<JWTPrincipal>()!!
                val user = principal.payload.subject ?: "unknown"
                val roles = principal.payload.getClaim("role")?.asList(String::class.java) ?: emptyList()
                
                val sessionId = UUID.randomUUID().toString()
                val session = SseSession(id = sessionId)
                sseSessions[sessionId] = session
                
                logger.info { "╔════════════════════════════════════════════════════════════════╗" }
                logger.info { "║ SSE CLIENT CONNECTED (MCP Inspector)                          ║" }
                logger.info { "║ Session: $sessionId" }
                logger.info { "║ User:    $user  Roles: $roles" }
                logger.info { "╚════════════════════════════════════════════════════════════════╝" }
                
                // Determine the base URL for the message endpoint
                val host = call.request.host()
                val port = call.request.port()
                val scheme = if (call.request.local.scheme == "https") "https" else "http"
                val messageEndpoint = "$scheme://$host:$port/message?sessionId=$sessionId"
                
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        // Send the endpoint event first (tells Inspector where to POST messages)
                        write("event: endpoint\n")
                        write("data: $messageEndpoint\n\n")
                        flush()
                        
                        logger.info { "SSE: Sent endpoint event: $messageEndpoint" }
                        
                        // Keep connection alive and forward responses from the channel
                        while (true) {
                            // Wait for messages with timeout for keepalive
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
                                // Send keepalive comment
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
                
                logger.info { "╔════════════════════════════════════════════════════════════════╗" }
                logger.info { "║ MCP REQUEST via SSE (MCP Inspector)                           ║" }
                logger.info { "║ User: $user                                                   ║" }
                logger.info { "╚════════════════════════════════════════════════════════════════╝" }
                
                try {
                    val response = processMcpMessage(mcpServer, requestBody, mcpHandler)
                    
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
        
        // ─────────────────────────────────────────────────────────────────────
        // Internal service-to-service endpoints (no user auth required)
        // These are only reachable within the Docker network
        // ─────────────────────────────────────────────────────────────────────
        
        // Callback endpoint (receives results from Executor)
        callbackRoutes()
        
        // Context endpoint (Executor fetches request body here)
        contextRoutes()
    }
    
    // Periodic cleanup of expired contexts and stale SSE sessions
    val cleanupJob = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            delay(60_000) // Every minute
            ContextStore.cleanupExpired()
            
            // Clean up stale SSE sessions (older than 1 hour)
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
    
    // Cancel cleanup and close RabbitMQ on shutdown
    environment.monitor.subscribe(ApplicationStopped) {
        cleanupJob.cancel()
        ExecutorPublisher.shutdown()
    }
}

/**
 * Respond with RFC 9728 Protected Resource Metadata.
 * Points to the Gateway itself as the authorization server (the Gateway
 * proxies /authorize and /token to Keycloak, avoiding cross-origin issues).
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
            // Point to Gateway itself - it proxies to Keycloak
            // This avoids CORS issues between Inspector and Keycloak
            add(resourceUrl)
        }
        putJsonArray("bearer_methods_supported") {
            add("header")
            add("query")
        }
    }
    
    logger.info { "Protected resource metadata requested - auth server: $resourceUrl (proxying to $keycloakIssuer)" }
    
    call.respondText(
        Json.encodeToString(JsonObject.serializer(), metadata),
        ContentType.Application.Json
    )
}

/**
 * Respond with RFC 8414 Authorization Server Metadata.
 * The Gateway acts as an OAuth facade: /authorize redirects to Keycloak,
 * /token proxies to Keycloak. This keeps everything on a single origin
 * so MCP Inspector doesn't hit CORS issues.
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
        // These point to Gateway endpoints that proxy to Keycloak
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
            add("none")  // Public client
        }
        putJsonArray("scopes_supported") {
            add("openid")
            add("profile")
            add("email")
        }
    }
    
    logger.info { "OAuth auth server metadata requested - facade at $gatewayUrl (proxying to $keycloakIssuer)" }
    
    call.respondText(
        Json.encodeToString(JsonObject.serializer(), metadata),
        ContentType.Application.Json
    )
}

/**
 * Process an MCP JSON-RPC message with verbose logging.
 * Shows the complete message flow for demonstration purposes.
 */
private suspend fun processMcpMessage(
    server: io.modelcontextprotocol.kotlin.sdk.server.Server, 
    message: String,
    mcpHandler: McpServerHandler
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
        
        // Log incoming request
        logMcpMessage("INCOMING", "LLM → Gateway", method ?: "unknown", message)
        
        // Check if this is a notification (no id) - notifications don't get responses
        val isNotification = id == null || id is JsonNull
        
        val result = when (method) {
            // Handle notifications (no response expected)
            "notifications/initialized" -> {
                logger.info { "Client sent initialized notification - handshake complete" }
                null // No response for notifications
            }
            
            "notifications/cancelled" -> {
                logger.info { "Client cancelled a request" }
                null // No response for notifications
            }
            
            "initialize" -> {
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        put("protocolVersion", "2024-11-05")
                        putJsonObject("serverInfo") {
                            put("name", "noumena-mcp-gateway")
                            put("version", "1.0.0")
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
            
            "tools/list" -> {
                // Load tools dynamically from services.yaml
                val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
                val allTools = try {
                    ServicesConfigLoader.load(configPath).services
                        .filter { it.enabled }
                        .flatMap { svc -> svc.tools.filter { it.enabled } }
                } catch (e: Exception) {
                    logger.warn { "Failed to load services config: ${e.message}. Using empty tool list." }
                    emptyList()
                }
                
                logger.info { "Returning ${allTools.size} tools from dynamic config" }
                
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        putJsonArray("tools") {
                            allTools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    putJsonObject("inputSchema") {
                                        put("type", tool.inputSchema.type)
                                        putJsonObject("properties") {
                                            tool.inputSchema.properties.forEach { (propName, propSchema) ->
                                                putJsonObject(propName) {
                                                    put("type", propSchema.type)
                                                    put("description", propSchema.description)
                                                }
                                            }
                                        }
                                        if (tool.inputSchema.required.isNotEmpty()) {
                                            putJsonArray("required") {
                                                tool.inputSchema.required.forEach { add(it) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            "tools/call" -> {
                val toolName = params?.get("name")?.jsonPrimitive?.content ?: "unknown"
                val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}
                
                logger.info { "╔══════════════════════════════════════════════════════════════╗" }
                logger.info { "║ TOOL CALL: $toolName" }
                logger.info { "╠══════════════════════════════════════════════════════════════╣" }
                logger.info { "║ Arguments: ${json.encodeToString(JsonObject.serializer(), arguments).take(60)}..." }
                logger.info { "╚══════════════════════════════════════════════════════════════╝" }
                
                // Look up which service owns this tool
                val serviceForTool = findServiceForTool(toolName)
                val service = serviceForTool?.first ?: "unknown"
                
                // Check if the service is enabled before executing
                val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
                val serviceConfig = try {
                    ServicesConfigLoader.load(configPath).services.find { it.name == service }
                } catch (e: Exception) {
                    null
                }
                
                if (serviceConfig == null || !serviceConfig.enabled) {
                    logger.warn { "Service '$service' is not enabled or not found for tool '$toolName'. Rejecting tool call." }
                    val errorResponse = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonNull)
                        putJsonObject("result") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Error: Service '$service' is disabled or not found. Tool call rejected.")
                                }
                            }
                            put("isError", true)
                        }
                    }
                    return@processMcpMessage json.encodeToString(JsonObject.serializer(), errorResponse)
                }
                
                // Check if the specific tool is enabled
                val toolConfig = serviceConfig.tools.find { it.name == toolName }
                if (toolConfig != null && !toolConfig.enabled) {
                    logger.warn { "Tool '$toolName' is disabled. Rejecting tool call." }
                    val errorResponse = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id ?: JsonNull)
                        putJsonObject("result") {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Error: Tool '$toolName' is disabled. Tool call rejected.")
                                }
                            }
                            put("isError", true)
                        }
                    }
                    return@processMcpMessage json.encodeToString(JsonObject.serializer(), errorResponse)
                }
                
                // Call the actual handler
                val toolResult = mcpHandler.handleToolCallDirect(
                    service = service,
                    operation = toolName,
                    arguments = arguments
                )
                
                // Build JSON-RPC response
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    putJsonObject("result") {
                        putJsonArray("content") {
                            toolResult.content.forEach { content ->
                                addJsonObject {
                                    put("type", "text")
                                    put("text", (content as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text ?: content.toString())
                                }
                            }
                        }
                        put("isError", toolResult.isError ?: false)
                    }
                }
                json.encodeToString(JsonObject.serializer(), response)
            }
            
            else -> {
                // For unknown notifications (no id), just ignore them
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
        
        // Log outgoing response (only if there is one)
        if (result != null) {
            logMcpMessage("OUTGOING", "Gateway → LLM", method ?: "unknown", result)
        }
        
        result ?: ""
    } catch (e: Exception) {
        logger.error(e) { "Error processing MCP message" }
        val errorResponse = """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: ${e.message}"}}"""
        logMcpMessage("ERROR", "Gateway → LLM", "error", errorResponse)
        errorResponse
    }
}

/**
 * Find the service that owns a given tool by looking it up in the config.
 * Returns the service name and the tool name as-is.
 */
private fun findServiceForTool(toolName: String): Pair<String, String>? {
    val configPath = System.getenv("SERVICES_CONFIG_PATH") ?: "/app/configs/services.yaml"
    return try {
        val config = ServicesConfigLoader.load(configPath)
        for (service in config.services) {
            for (tool in service.tools) {
                if (tool.name == toolName) {
                    return service.name to toolName
                }
            }
        }
        null
    } catch (e: Exception) {
        logger.warn { "Failed to look up tool '$toolName' in config: ${e.message}" }
        null
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
