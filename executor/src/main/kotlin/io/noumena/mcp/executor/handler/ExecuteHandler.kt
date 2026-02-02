package io.noumena.mcp.executor.handler

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.executor.upstream.UpstreamRouter
import io.noumena.mcp.executor.callback.CallbackSender
import io.noumena.mcp.shared.models.ExecuteRequest
import io.noumena.mcp.shared.models.ExecuteResult
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Execute routes - receives execution requests from NPL Engine via HTTP Bridge.
 * 
 * SECURITY: This endpoint is protected by network isolation.
 * - In Docker: Executor is not exposed to host network
 * - In Kubernetes: NetworkPolicy restricts access to NPL Engine only
 * 
 * No cryptographic signature verification is needed because:
 * 1. Only NPL can reach this endpoint (network isolation)
 * 2. NPL's HTTP Bridge handles the actual HTTP call
 */
fun Route.executeRoutes() {
    val vaultClient = VaultClient()
    val upstreamRouter = UpstreamRouter(vaultClient)
    val callbackSender = CallbackSender()
    
    route("/execute") {
        post {
            val request = call.receive<ExecuteRequest>()
            
            logger.info { "Received execute request: ${request.requestId} for ${request.service}.${request.operation}" }
            
            // Acknowledge receipt immediately
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "executing"))
            
            // Execute asynchronously
            application.launch {
                val result = try {
                    // Fetch credentials from Vault and call upstream
                    val upstreamResult = upstreamRouter.route(request)
                    
                    ExecuteResult(
                        requestId = request.requestId,
                        success = true,
                        data = upstreamResult
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Execution failed for ${request.requestId}" }
                    
                    ExecuteResult(
                        requestId = request.requestId,
                        success = false,
                        error = e.message ?: "Unknown error"
                    )
                }
                
                // Send result to Gateway via callback
                callbackSender.send(request.callbackUrl, result)
            }
        }
    }
}
