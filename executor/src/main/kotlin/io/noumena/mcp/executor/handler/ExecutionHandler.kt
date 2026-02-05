package io.noumena.mcp.executor.handler

import io.noumena.mcp.executor.gateway.GatewayClient
import io.noumena.mcp.executor.npl.NplExecutorClient
import io.noumena.mcp.executor.secrets.VaultClient
import io.noumena.mcp.executor.upstream.UpstreamRouter
import io.noumena.mcp.shared.models.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handles execution of approved requests.
 * 
 * Flow:
 * 1. Receive notification from RabbitMQ
 * 2. Validate with NPL (defense in depth)
 * 3. Fetch context from Gateway (full request body)
 * 4. Get secrets from Vault
 * 5. Call upstream MCP/REST
 * 6. Report completion to NPL (audit)
 * 7. Send result to Gateway (callback)
 */
class ExecutionHandler(
    private val nplClient: NplExecutorClient = NplExecutorClient(),
    private val gatewayClient: GatewayClient = GatewayClient(),
    private val vaultClient: VaultClient = VaultClient(),
    private val upstreamRouter: UpstreamRouter = UpstreamRouter(vaultClient)
) {
    
    private val devMode = System.getenv("DEV_MODE")?.toBoolean() ?: false
    
    /**
     * Handle an execution notification from RabbitMQ.
     */
    suspend fun handle(notification: ExecutionNotification) {
        val requestId = notification.requestId
        val startTime = System.currentTimeMillis()
        
        logger.info { "╔════════════════════════════════════════════════════════════════╗" }
        logger.info { "║ EXECUTION HANDLER - Processing Request                          ║" }
        logger.info { "╠════════════════════════════════════════════════════════════════╣" }
        logger.info { "║ Request ID:  $requestId" }
        logger.info { "║ Service:     ${notification.service}" }
        logger.info { "║ Operation:   ${notification.operation}" }
        logger.info { "║ Gateway URL: ${notification.gatewayUrl}" }
        logger.info { "║ Callback:    ${notification.callbackUrl}" }
        logger.info { "╚════════════════════════════════════════════════════════════════╝" }
        
        try {
            // Step 1: Validate with NPL (defense in depth)
            if (!devMode) {
                val validationResult = nplClient.validate(
                    NplValidationRequest(
                        requestId = requestId,
                        service = notification.service,
                        operation = notification.operation
                    )
                )
                
                if (!validationResult.valid) {
                    logger.warn { "NPL validation failed for $requestId: ${validationResult.reason}" }
                    sendFailure(notification, "NPL validation failed: ${validationResult.reason}", startTime)
                    return
                }
                
                logger.info { "NPL validation passed for $requestId" }
            } else {
                logger.warn { "DEV_MODE: Skipping NPL validation" }
            }
            
            // Step 2: Fetch context from Gateway
            val context = gatewayClient.fetchContext(notification.gatewayUrl, requestId)
            
            if (context == null) {
                logger.error { "Failed to fetch context for $requestId" }
                sendFailure(notification, "Context not found or already consumed", startTime)
                return
            }
            
            logger.info { "Fetched context for $requestId: ${context.service}.${context.operation}" }
            
            // Step 3: Build execute request from context
            val executeRequest = ExecuteRequest(
                requestId = requestId,
                tenantId = context.tenantId,
                userId = context.userId,
                service = context.service,
                operation = context.operation,
                params = context.body,
                callbackUrl = notification.callbackUrl
            )
            
            // Step 4: Get secrets from Vault and call upstream
            val upstreamResult = upstreamRouter.routeWithFullResult(executeRequest)
            
            val durationMs = System.currentTimeMillis() - startTime
            
            // Step 5: Report completion to NPL (audit)
            nplClient.reportCompletion(
                NplResumeRequest(
                    requestId = requestId,
                    success = !upstreamResult.isError,
                    error = if (upstreamResult.isError) upstreamResult.data["result_0"] else null,
                    durationMs = durationMs
                )
            )
            
            // Step 6: Send result to Gateway
            val result = if (upstreamResult.isError) {
                ExecuteResult(
                    requestId = requestId,
                    success = false,
                    data = upstreamResult.data,
                    error = upstreamResult.data["result_0"] ?: "Upstream tool reported error",
                    mcpContent = upstreamResult.mcpContent,
                    mcpIsError = true
                )
            } else {
                ExecuteResult(
                    requestId = requestId,
                    success = true,
                    data = upstreamResult.data,
                    mcpContent = upstreamResult.mcpContent,
                    mcpIsError = false
                )
            }
            
            gatewayClient.sendCallback(notification.callbackUrl, result)
            
            logger.info { "Completed execution for $requestId in ${durationMs}ms" }
            
        } catch (e: Exception) {
            logger.error(e) { "Execution failed for $requestId" }
            sendFailure(notification, e.message ?: "Unknown error", startTime)
        }
    }
    
    /**
     * Send a failure result to Gateway.
     */
    private suspend fun sendFailure(
        notification: ExecutionNotification,
        error: String,
        startTime: Long
    ) {
        val durationMs = System.currentTimeMillis() - startTime
        
        // Report failure to NPL
        nplClient.reportCompletion(
            NplResumeRequest(
                requestId = notification.requestId,
                success = false,
                error = error,
                durationMs = durationMs
            )
        )
        
        // Send failure to Gateway
        gatewayClient.sendCallback(
            notification.callbackUrl,
            ExecuteResult(
                requestId = notification.requestId,
                success = false,
                error = error
            )
        )
    }
}
