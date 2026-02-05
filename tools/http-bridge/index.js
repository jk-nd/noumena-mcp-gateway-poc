/**
 * Simple HTTP Bridge for local development
 * 
 * Replaces the HTTP Connector for local POC - no network validation.
 * Connects to RabbitMQ, receives HttpRequestExecutionMessage notifications,
 * makes HTTP requests, and returns responses via NPL Engine resume API.
 */

const { Connection, Receiver } = require('rhea-promise');
const axios = require('axios');

// Configuration from environment
const AMQP_HOST = process.env.AMQP_HOST || 'localhost';
const AMQP_PORT = parseInt(process.env.AMQP_PORT || '5672');
const AMQP_USERNAME = process.env.AMQP_USERNAME || 'guest';
const AMQP_PASSWORD = process.env.AMQP_PASSWORD || 'guest';
const AMQP_QUEUE = process.env.AMQP_QUEUE_NAME || 'npl-notifications';
const ENGINE_URL = process.env.ENGINE_URL || 'http://localhost:12000';
const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:11000';
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || 'mcpgateway';
const KEYCLOAK_CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || 'admin-cli';
const KEYCLOAK_USERNAME = process.env.KEYCLOAK_USERNAME || 'admin';
const KEYCLOAK_PASSWORD = process.env.KEYCLOAK_PASSWORD || 'Welcome123';

let accessToken = null;
let tokenExpiry = 0;

/**
 * Get access token from Keycloak
 */
async function getAccessToken() {
    const now = Date.now();
    if (accessToken && now < tokenExpiry - 30000) {
        return accessToken;
    }

    console.log('[Auth] Fetching new access token from Keycloak...');
    const response = await axios.post(
        `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`,
        new URLSearchParams({
            grant_type: 'password',
            client_id: KEYCLOAK_CLIENT_ID,
            username: KEYCLOAK_USERNAME,
            password: KEYCLOAK_PASSWORD,
        }),
        {
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Host': 'keycloak:11000'  // For issuer URL in Docker
            }
        }
    );

    accessToken = response.data.access_token;
    tokenExpiry = now + (response.data.expires_in * 1000);
    console.log('[Auth] Token obtained, expires in', response.data.expires_in, 'seconds');
    return accessToken;
}

/**
 * Parse NPL HttpRequest from notification
 */
function parseHttpRequest(args) {
    const httpRequest = args[0];
    if (!httpRequest || httpRequest.nplType !== 'struct') {
        throw new Error('Invalid HttpRequest structure');
    }

    const value = httpRequest.value;
    const method = value.method?.variant || 'GET';
    const url = value.url?.value;
    const data = parseHttpData(value.data);

    return { method, url, data };
}

/**
 * Parse NPL HttpData union type
 */
function parseHttpData(data) {
    if (!data) return null;
    
    switch (data.nplType) {
        case 'text':
            return data.value;
        case 'number':
            return data.value;
        case 'list':
            if (data.value && data.value[0]?.nplType === 'struct' && 
                data.value[0]?.prototypeId?.includes('Pair')) {
                // List of Pairs -> object
                const obj = {};
                for (const pair of data.value) {
                    const key = pair.value.first?.value;
                    const val = parseHttpData(pair.value.second);
                    if (key) obj[key] = val;
                }
                return obj;
            }
            return data.value?.map(v => parseHttpData(v));
        default:
            return data.value;
    }
}

/**
 * Build NPL HttpResponse structure using full NPL wire format
 * Based on blockchain-connector email handler implementation
 */
function buildHttpResponse(statusCode, data, success = true) {
    return {
        nplType: 'struct',
        prototypeId: '/mcp-1.0?/connector/v1/http/HttpResponse',
        value: {
            executionStatus: {
                nplType: 'enum',
                prototypeId: '/mcp-1.0?/connector/v1/http/ExecutionStatus',
                variant: success ? 'Success' : 'Failure'
            },
            statusCode: {
                nplType: 'number',
                value: statusCode
            },
            data: {
                nplType: 'text',
                value: typeof data === 'string' ? data : JSON.stringify(data)
            }
        }
    };
}

/**
 * Send response back to NPL Engine via resume API
 */
async function sendResume(refId, callback, response) {
    const token = await getAccessToken();
    
    // The resume endpoint format
    const resumeUrl = `${ENGINE_URL}/npl/services/EmailTool/${refId}/${callback}`;
    
    // NPL action arguments use full wire format with nplType/prototypeId/value
    // Format discovered from blockchain-connector codegen GenerateActionTest.kt:
    // prototypeId: "/ssc-1.0.0?/lang/core/NotifySuccess</token/SmartContractFunctionCallResult>"
    // Note: inner type path doesn't include version prefix
    const notifyResult = {
        nplType: 'struct',
        prototypeId: '/mcp-1.0?/lang/core/NotifySuccess</connector/v1/http/HttpResponse>',
        value: {
            result: response
        }
    };
    
    // Body is array of arguments (like exerciseAction API)
    const body = [notifyResult];

    console.log('[Resume] Calling', resumeUrl);
    console.log('[Resume] Body:', JSON.stringify(body, null, 2));
    
    try {
        const result = await axios.post(resumeUrl, body, {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            }
        });
        console.log('[Resume] Success:', result.status);
    } catch (error) {
        console.error('[Resume] Failed:', error.response?.status, error.response?.data || error.message);
    }
}

/**
 * Handle incoming AMQP message
 */
async function handleMessage(context) {
    const message = context.message;
    
    try {
        const body = JSON.parse(message.body);
        console.log('\n[Message] Received notification:', body.name);
        
        // Check if it's HttpRequestExecutionMessage
        if (!body.name?.includes('HttpRequestExecutionMessage')) {
            console.log('[Message] Skipping non-HTTP message');
            context.delivery.accept();
            return;
        }

        const { method, url, data } = parseHttpRequest(body.arguments);
        console.log('[HTTP] Making request:', method, url);
        console.log('[HTTP] Data:', JSON.stringify(data, null, 2));

        // Make the HTTP request
        let response;
        try {
            const result = await axios({
                method: method.toLowerCase(),
                url: url,
                data: data,
                timeout: 30000,
                validateStatus: () => true  // Accept any status
            });
            
            console.log('[HTTP] Response status:', result.status);
            response = buildHttpResponse(result.status, result.data, result.status >= 200 && result.status < 300);
        } catch (error) {
            console.error('[HTTP] Request failed:', error.message);
            response = buildHttpResponse(500, { error: error.message }, false);
        }

        // Send resume to NPL Engine
        await sendResume(body.refId, body.callback, response);
        
        context.delivery.accept();
        console.log('[Message] Processed successfully');
        
    } catch (error) {
        console.error('[Message] Error processing:', error.message);
        context.delivery.reject({ condition: 'amqp:internal-error', description: error.message });
    }
}

/**
 * Main entry point
 */
async function main() {
    console.log('='.repeat(60));
    console.log('  HTTP Bridge for Noumena MCP Gateway (Local Development)');
    console.log('='.repeat(60));
    console.log('Config:');
    console.log('  AMQP:', `${AMQP_HOST}:${AMQP_PORT}`);
    console.log('  Queue:', AMQP_QUEUE);
    console.log('  Engine:', ENGINE_URL);
    console.log('  Keycloak:', KEYCLOAK_URL);
    console.log('');

    const connection = new Connection({
        host: AMQP_HOST,
        port: AMQP_PORT,
        username: AMQP_USERNAME,
        password: AMQP_PASSWORD,
        transport: 'tcp',
        reconnect: true,
    });

    connection.on('connection_error', (context) => {
        console.error('[AMQP] Connection error:', context.error);
    });
    
    connection.on('disconnected', (context) => {
        console.log('[AMQP] Disconnected, will attempt to reconnect...');
    });

    try {
        console.log('[AMQP] Attempting to connect...');
        await connection.open();
        console.log('[AMQP] Connected to RabbitMQ');

        console.log('[AMQP] Creating receiver for queue:', AMQP_QUEUE);
        const receiver = await connection.createReceiver({
            source: { address: AMQP_QUEUE },
            credit_window: 1,
            autoaccept: false,
        });

        receiver.on('message', handleMessage);
        receiver.on('receiver_error', (context) => {
            console.error('[AMQP] Receiver error:', context.receiver?.error || 'unknown error');
        });
        receiver.on('receiver_open', () => {
            console.log('[AMQP] Receiver opened successfully');
        });
        receiver.on('receiver_close', (context) => {
            console.log('[AMQP] Receiver closed:', context.receiver?.error || 'no error');
        });

        console.log('[AMQP] Listening for messages on queue:', AMQP_QUEUE);
        console.log('\nReady to process HttpRequestExecutionMessage notifications...\n');

    } catch (error) {
        console.error('[AMQP] Connection failed:', error.message || error);
        console.error('[AMQP] Full error:', JSON.stringify(error, null, 2));
        process.exit(1);
    }
}

main();
