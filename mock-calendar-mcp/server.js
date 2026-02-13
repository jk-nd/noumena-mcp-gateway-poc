/**
 * Mock Calendar MCP Server — Streamable HTTP transport
 *
 * Implements MCP 2025-03-26 Streamable HTTP:
 *   POST /mcp  — JSON-RPC request/response (initialize, tools/list, tools/call)
 *   GET  /mcp  — SSE notification stream (server-initiated events)
 *
 * Used for testing bilateral streaming through the Envoy AI Gateway.
 */

const express = require('express');
const { v4: uuidv4 } = require('uuid');

const app = express();
const PORT = process.env.PORT || 8000;

// ── Session store ──────────────────────────────────────────────────────────
const sessions = new Map(); // sessionId -> { initialized, sseClients: Set }

// ── Mock calendar data ─────────────────────────────────────────────────────
const MOCK_EVENTS = [
  { id: 'evt-1', title: 'Sprint Planning', start: '2026-02-13T09:00:00Z', end: '2026-02-13T10:00:00Z', attendees: ['alice@acme.com', 'bob@acme.com'] },
  { id: 'evt-2', title: 'Lunch with Dave', start: '2026-02-13T12:00:00Z', end: '2026-02-13T13:00:00Z', attendees: ['dave@acme.com'] },
  { id: 'evt-3', title: 'Architecture Review', start: '2026-02-13T14:00:00Z', end: '2026-02-13T15:30:00Z', attendees: ['alice@acme.com', 'carol@acme.com'] },
  { id: 'evt-4', title: 'Standup', start: '2026-02-14T09:00:00Z', end: '2026-02-14T09:15:00Z', attendees: ['alice@acme.com', 'bob@acme.com', 'carol@acme.com'] },
];

const NOTIFICATION_MESSAGES = [
  'Meeting "Sprint Planning" starting in 5 minutes',
  'Reminder: "Lunch with Dave" at 12:00 PM',
  'Calendar updated: "Architecture Review" moved to 3:00 PM',
  'New invite: "Budget Review" from carol@acme.com',
  'Meeting "Standup" starting in 5 minutes',
  '"Architecture Review" cancelled by alice@acme.com',
];

// ── Server capabilities ────────────────────────────────────────────────────
const SERVER_INFO = {
  name: 'mock-calendar-mcp',
  version: '1.0.0',
};

const SERVER_CAPABILITIES = {
  tools: { listChanged: true },
};

const TOOLS = [
  {
    name: 'list_events',
    description: 'List calendar events for a given date or date range',
    inputSchema: {
      type: 'object',
      properties: {
        date: { type: 'string', description: 'Date in YYYY-MM-DD format' },
      },
      required: ['date'],
    },
  },
  {
    name: 'create_event',
    description: 'Create a new calendar event',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string', description: 'Event title' },
        start: { type: 'string', description: 'Start time in ISO 8601 format' },
        end: { type: 'string', description: 'End time in ISO 8601 format' },
        attendees: { type: 'array', items: { type: 'string' }, description: 'List of attendee emails' },
      },
      required: ['title', 'start', 'end'],
    },
  },
];

// ── Middleware ──────────────────────────────────────────────────────────────
app.use(express.json());

// ── POST /mcp — JSON-RPC request handler ───────────────────────────────────
app.post('/mcp', (req, res) => {
  const { jsonrpc, id, method, params } = req.body;

  if (jsonrpc !== '2.0') {
    return res.status(400).json({ jsonrpc: '2.0', id, error: { code: -32600, message: 'Invalid JSON-RPC version' } });
  }

  let sessionId = req.headers['mcp-session-id'];

  // Route by method
  switch (method) {
    case 'initialize': {
      sessionId = uuidv4();
      sessions.set(sessionId, { initialized: true, sseClients: new Set() });
      console.log(`[session ${sessionId}] initialized`);

      res.setHeader('Mcp-Session-Id', sessionId);
      return res.json({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: '2025-03-26',
          serverInfo: SERVER_INFO,
          capabilities: SERVER_CAPABILITIES,
        },
      });
    }

    case 'notifications/initialized': {
      // Client acknowledges initialization — no response needed for notifications
      console.log(`[session ${sessionId || 'unknown'}] client initialized`);
      return res.status(204).send();
    }

    case 'tools/list': {
      if (sessionId) res.setHeader('Mcp-Session-Id', sessionId);
      return res.json({
        jsonrpc: '2.0',
        id,
        result: { tools: TOOLS },
      });
    }

    case 'tools/call': {
      if (sessionId) res.setHeader('Mcp-Session-Id', sessionId);
      const result = handleToolCall(params);
      return res.json({
        jsonrpc: '2.0',
        id,
        result,
      });
    }

    default:
      return res.status(400).json({
        jsonrpc: '2.0',
        id,
        error: { code: -32601, message: `Method not found: ${method}` },
      });
  }
});

// ── GET /mcp — SSE notification stream ─────────────────────────────────────
app.get('/mcp', (req, res) => {
  const sessionId = req.headers['mcp-session-id'];

  console.log(`[SSE] new notification stream (session: ${sessionId || 'none'})`);

  // Set SSE headers
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  if (sessionId) res.setHeader('Mcp-Session-Id', sessionId);

  res.flushHeaders();

  // Track this client
  const session = sessionId ? sessions.get(sessionId) : null;
  if (session) session.sseClients.add(res);

  // Send initial keepalive
  res.write(': keepalive\n\n');

  // Push mock notifications every 10 seconds
  let notifIndex = 0;
  const interval = setInterval(() => {
    const message = NOTIFICATION_MESSAGES[notifIndex % NOTIFICATION_MESSAGES.length];
    const notification = {
      jsonrpc: '2.0',
      method: 'notifications/message',
      params: {
        level: 'info',
        logger: 'mock-calendar',
        data: message,
      },
    };

    res.write(`event: message\ndata: ${JSON.stringify(notification)}\n\n`);
    console.log(`[SSE] sent notification: ${message}`);
    notifIndex++;
  }, 10000);

  // Cleanup on disconnect
  req.on('close', () => {
    clearInterval(interval);
    if (session) session.sseClients.delete(res);
    console.log(`[SSE] client disconnected (session: ${sessionId || 'none'})`);
  });
});

// ── Health check ───────────────────────────────────────────────────────────
app.get('/health', (_req, res) => {
  res.json({ status: 'healthy', service: 'mock-calendar-mcp' });
});

// ── Tool call handler ──────────────────────────────────────────────────────
function handleToolCall(params) {
  const { name, arguments: args } = params;

  switch (name) {
    case 'list_events': {
      const date = args?.date || '2026-02-13';
      const events = MOCK_EVENTS.filter((e) => e.start.startsWith(date));
      return {
        content: [
          {
            type: 'text',
            text: events.length > 0
              ? JSON.stringify(events, null, 2)
              : `No events found for ${date}`,
          },
        ],
      };
    }

    case 'create_event': {
      const newEvent = {
        id: `evt-${uuidv4().slice(0, 8)}`,
        title: args.title,
        start: args.start,
        end: args.end,
        attendees: args.attendees || [],
      };
      MOCK_EVENTS.push(newEvent);
      return {
        content: [
          {
            type: 'text',
            text: `Event created: ${JSON.stringify(newEvent, null, 2)}`,
          },
        ],
      };
    }

    default:
      return {
        content: [{ type: 'text', text: `Unknown tool: ${name}` }],
        isError: true,
      };
  }
}

// ── Start server ───────────────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log(`Mock Calendar MCP server listening on port ${PORT}`);
  console.log(`  POST /mcp  — JSON-RPC (initialize, tools/list, tools/call)`);
  console.log(`  GET  /mcp  — SSE notification stream`);
  console.log(`  GET  /health — Health check`);
});
