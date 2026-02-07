#!/usr/bin/env node
/**
 * Notification Test MCP Server
 *
 * A minimal MCP server (STDIO transport) for testing server-initiated notifications.
 * The server implements the MCP protocol and provides tools that trigger
 * server-initiated notifications — messages sent from server to client
 * outside the normal request-response flow.
 *
 * Tools:
 *   - echo: Simple echo tool (request-response baseline)
 *   - trigger_notification: Call this to make the server send a
 *     `notifications/tools/list_changed` notification after responding
 *   - send_log: Sends a `notifications/message` (logging notification)
 *     after responding to the tool call
 *
 * Usage:
 *   docker build -t mcp-notification-test .
 *   docker run -i --rm mcp-notification-test
 */

const readline = require('readline');

const rl = readline.createInterface({
  input: process.stdin,
  terminal: false,
  crlfDelay: Infinity,
});

/**
 * Send a JSON-RPC message to stdout (client).
 */
function send(msg) {
  const str = JSON.stringify(msg);
  process.stdout.write(str + '\n');
}

/**
 * Log to stderr (not part of MCP protocol; visible in container logs).
 */
function log(message) {
  process.stderr.write(`[notification-test-server] ${message}\n`);
}

// Tool definitions
const TOOLS = [
  {
    name: 'echo',
    description: 'Echo back the provided message (standard request-response)',
    inputSchema: {
      type: 'object',
      properties: {
        message: { type: 'string', description: 'Message to echo back' },
      },
      required: ['message'],
    },
  },
  {
    name: 'trigger_notification',
    description:
      'Triggers a server-initiated notifications/tools/list_changed notification after responding. ' +
      'This tests the Gateway notification forwarding pipeline.',
    inputSchema: {
      type: 'object',
      properties: {
        delay_ms: {
          type: 'number',
          description: 'Delay in milliseconds before sending the notification (default: 1000)',
        },
      },
    },
  },
  {
    name: 'send_log',
    description:
      'Sends a server-initiated notifications/message (logging) notification after responding. ' +
      'This tests forwarding of log-level notifications.',
    inputSchema: {
      type: 'object',
      properties: {
        level: {
          type: 'string',
          description: 'Log level: debug, info, warning, error (default: info)',
        },
        log_message: {
          type: 'string',
          description: 'The log message to send (default: "Hello from upstream MCP!")',
        },
      },
    },
  },
];

/**
 * Handle an incoming JSON-RPC message.
 */
function handleMessage(msg) {
  switch (msg.method) {
    case 'initialize':
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: {
          protocolVersion: '2024-11-05',
          serverInfo: { name: 'notification-test-server', version: '1.0.0' },
          capabilities: {
            tools: { listChanged: true },
            logging: {},
          },
        },
      });
      log('Initialized - capabilities: tools (listChanged), logging');
      break;

    case 'notifications/initialized':
      log('Client initialized - handshake complete');
      break;

    case 'tools/list':
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: { tools: TOOLS },
      });
      log(`Listed ${TOOLS.length} tools`);
      break;

    case 'tools/call':
      handleToolCall(msg);
      break;

    case 'ping':
      send({ jsonrpc: '2.0', id: msg.id, result: {} });
      break;

    default:
      if (msg.id !== undefined && msg.id !== null) {
        send({
          jsonrpc: '2.0',
          id: msg.id,
          error: {
            code: -32601,
            message: `Method not found: ${msg.method}`,
          },
        });
      }
  }
}

/**
 * Handle a tools/call request.
 */
function handleToolCall(msg) {
  const name = msg.params?.name;
  const args = msg.params?.arguments || {};

  switch (name) {
    case 'echo':
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: {
          content: [
            { type: 'text', text: `Echo: ${args.message || '(empty)'}` },
          ],
        },
      });
      break;

    case 'trigger_notification': {
      const delayMs = Math.min(Math.max(args.delay_ms || 1000, 100), 10000);

      // Respond to the tool call first
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: {
          content: [
            {
              type: 'text',
              text: `Tool call completed. A notifications/tools/list_changed notification will be sent in ${delayMs}ms.`,
            },
          ],
        },
      });

      // Send the server-initiated notification after a delay
      setTimeout(() => {
        log('Sending notifications/tools/list_changed...');
        send({
          jsonrpc: '2.0',
          method: 'notifications/tools/list_changed',
        });
        log('Notification sent!');
      }, delayMs);
      break;
    }

    case 'send_log': {
      const level = args.level || 'info';
      const logMessage = args.log_message || 'Hello from upstream MCP!';

      // Respond to the tool call first
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: {
          content: [
            {
              type: 'text',
              text: `Tool call completed. A notifications/message (level=${level}) will be sent in 500ms.`,
            },
          ],
        },
      });

      // Send the server-initiated logging notification after a delay
      setTimeout(() => {
        log(`Sending notifications/message (level=${level})...`);
        send({
          jsonrpc: '2.0',
          method: 'notifications/message',
          params: {
            level: level,
            logger: 'notification-test-server',
            data: logMessage,
          },
        });
        log('Log notification sent!');
      }, 500);
      break;
    }

    default:
      send({
        jsonrpc: '2.0',
        id: msg.id,
        result: {
          isError: true,
          content: [{ type: 'text', text: `Unknown tool: ${name}` }],
        },
      });
  }
}

// ─── Main ──────────────────────────────────────────────────────────────────

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;

  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch (e) {
    log(`Parse error: ${e.message}`);
    return;
  }

  try {
    handleMessage(msg);
  } catch (e) {
    log(`Error handling message: ${e.message}`);
    if (msg.id !== undefined && msg.id !== null) {
      send({
        jsonrpc: '2.0',
        id: msg.id,
        error: { code: -32603, message: `Internal error: ${e.message}` },
      });
    }
  }
});

rl.on('close', () => {
  log('stdin closed, shutting down');
  process.exit(0);
});

log('Notification Test MCP Server started (STDIO transport)');
