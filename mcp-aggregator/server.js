/**
 * MCP Aggregator — Multi-backend MCP routing gateway
 *
 * Sits between Envoy and backend MCP servers. Aggregates tools from all
 * backends and routes tool calls to the correct backend by namespace prefix.
 *
 * Implements MCP 2025-03-26 Streamable HTTP:
 *   POST   /mcp  — JSON-RPC (initialize, tools/list, tools/call, notifications)
 *   GET    /mcp  — SSE notification stream (multiplexed from all backends)
 *   DELETE /mcp  — Session termination
 */

const express = require('express');
const { v4: uuidv4 } = require('uuid');

const app = express();
const PORT = process.env.PORT || 8000;

// ── Backend configuration ─────────────────────────────────────────────────
// BACKENDS=duckduckgo:http://duckduckgo-mcp:8000,mock-calendar:http://mock-calendar-mcp:8000
const backends = new Map();
(process.env.BACKENDS || '').split(',').filter(Boolean).forEach((entry) => {
  const colonIdx = entry.indexOf(':');
  if (colonIdx === -1) return;
  const name = entry.slice(0, colonIdx).trim();
  const url = entry.slice(colonIdx + 1).trim();
  backends.set(name, url);
});

if (backends.size === 0) {
  console.error('FATAL: No backends configured. Set BACKENDS env var.');
  process.exit(1);
}

console.log(`Configured backends: ${[...backends.entries()].map(([n, u]) => `${n}=${u}`).join(', ')}`);

// ── Session store ─────────────────────────────────────────────────────────
// clientSessionId → { backendSessions: Map<serviceName, { url, sessionId }> }
const sessions = new Map();

// ── MCP Streamable HTTP headers ───────────────────────────────────────────
// Supergateway requires Accept to include both JSON and SSE per MCP spec
const MCP_HEADERS = {
  'Content-Type': 'application/json',
  'Accept': 'application/json, text/event-stream',
};

function backendHeaders(sessionId) {
  return {
    ...MCP_HEADERS,
    ...(sessionId ? { 'Mcp-Session-Id': sessionId } : {}),
  };
}

// Parse a response that may be JSON or SSE (extracts JSON-RPC from SSE data lines)
async function parseResponse(resp) {
  const contentType = resp.headers.get('content-type') || '';
  if (contentType.includes('text/event-stream')) {
    const text = await resp.text();
    // Extract JSON from SSE data: lines
    for (const line of text.split('\n')) {
      if (line.startsWith('data: ')) {
        try {
          return JSON.parse(line.slice(6));
        } catch { /* skip non-JSON data lines */ }
      }
    }
    return {};
  }
  return resp.json();
}

// ── Middleware ─────────────────────────────────────────────────────────────
app.use(express.json());

// ── POST /mcp — JSON-RPC request handler ──────────────────────────────────
app.post('/mcp', async (req, res) => {
  const { jsonrpc, id, method, params } = req.body;

  if (jsonrpc !== '2.0') {
    return res.status(400).json({
      jsonrpc: '2.0', id,
      error: { code: -32600, message: 'Invalid JSON-RPC version' },
    });
  }

  try {
    switch (method) {
      case 'initialize':
        return await handleInitialize(req, res, id, params);
      case 'notifications/initialized':
        return await handleNotificationsInitialized(req, res);
      case 'tools/list':
        return await handleToolsList(req, res, id, params);
      case 'tools/call':
        return await handleToolsCall(req, res, id, params);
      default:
        return await handleDefault(req, res, id, method, params);
    }
  } catch (err) {
    console.error(`[aggregator] error handling ${method}:`, err.message);
    return res.status(502).json({
      jsonrpc: '2.0', id,
      error: { code: -32603, message: `Aggregator error: ${err.message}` },
    });
  }
});

// ── initialize — fan out to all backends, merge capabilities ──────────────
async function handleInitialize(req, res, id, params) {
  const clientSessionId = uuidv4();
  const backendSessions = new Map();

  const results = await Promise.allSettled(
    [...backends.entries()].map(async ([name, url]) => {
      const resp = await fetch(`${url}/mcp`, {
        method: 'POST',
        headers: MCP_HEADERS,
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params }),
      });
      const backendSessionId = resp.headers.get('mcp-session-id');
      const body = await parseResponse(resp);
      return { name, url, backendSessionId, body };
    })
  );

  // Merge capabilities from all successful backends
  const mergedCapabilities = {};
  for (const result of results) {
    if (result.status !== 'fulfilled') {
      console.warn(`[aggregator] backend init failed:`, result.reason?.message);
      continue;
    }
    const { name, url, backendSessionId, body } = result.value;
    backendSessions.set(name, { url, sessionId: backendSessionId });
    console.log(`[aggregator] initialized ${name} (session: ${backendSessionId})`);

    // Union merge capabilities
    const caps = body.result?.capabilities || {};
    for (const [key, val] of Object.entries(caps)) {
      if (!mergedCapabilities[key]) {
        mergedCapabilities[key] = val;
      } else if (typeof val === 'object' && val !== null) {
        Object.assign(mergedCapabilities[key], val);
      }
    }
  }

  if (backendSessions.size === 0) {
    return res.status(502).json({
      jsonrpc: '2.0', id,
      error: { code: -32603, message: 'All backends failed to initialize' },
    });
  }

  sessions.set(clientSessionId, { backendSessions });
  console.log(`[aggregator] client session ${clientSessionId} → ${backendSessions.size} backends`);

  res.setHeader('Mcp-Session-Id', clientSessionId);
  return res.json({
    jsonrpc: '2.0',
    id,
    result: {
      protocolVersion: '2025-03-26',
      serverInfo: { name: 'MCP Gateway', version: '1.0.0' },
      capabilities: mergedCapabilities,
    },
  });
}

// ── notifications/initialized — fire-and-forget to all backends ───────────
async function handleNotificationsInitialized(req, res) {
  const session = getSession(req);
  if (session) {
    // Fire-and-forget to all backends
    for (const [name, backend] of session.backendSessions) {
      fetch(`${backend.url}/mcp`, {
        method: 'POST',
        headers: backendHeaders(backend.sessionId),
        body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
      }).catch((err) => console.warn(`[aggregator] notify ${name} failed:`, err.message));
    }
  }
  return res.status(204).send();
}

// ── tools/list — fan out to granted backends, prefix tool names ───────────
async function handleToolsList(req, res, id, params) {
  const session = getSession(req);
  let targets = session
    ? [...session.backendSessions.entries()]
    : [...backends.entries()].map(([name, url]) => [name, { url, sessionId: null }]);

  // Filter backends by x-granted-services header (set by OPA policy)
  const grantedHeader = req.headers['x-granted-services'];
  if (grantedHeader !== undefined) {
    const grantedServices = new Set(grantedHeader.split(',').filter(Boolean));
    targets = targets.filter(([name]) => grantedServices.has(name));
  }

  const results = await Promise.allSettled(
    targets.map(async ([name, backend]) => {
      const resp = await fetch(`${backend.url}/mcp`, {
        method: 'POST',
        headers: backendHeaders(backend.sessionId),
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params }),
      });
      const body = await parseResponse(resp);
      return { name, tools: body.result?.tools || [] };
    })
  );

  // Merge tools with service name prefix
  const allTools = [];
  for (const result of results) {
    if (result.status !== 'fulfilled') {
      console.warn(`[aggregator] tools/list failed:`, result.reason?.message);
      continue;
    }
    const { name, tools } = result.value;
    for (const tool of tools) {
      allTools.push({
        ...tool,
        name: `${name}.${tool.name}`,
      });
    }
  }

  const clientSessionId = req.headers['mcp-session-id'];
  if (clientSessionId) res.setHeader('Mcp-Session-Id', clientSessionId);

  return res.json({
    jsonrpc: '2.0',
    id,
    result: { tools: allTools },
  });
}

// ── tools/call — route to correct backend by namespace prefix ─────────────
async function handleToolsCall(req, res, id, params) {
  const session = getSession(req);
  if (!session) {
    return res.status(400).json({
      jsonrpc: '2.0', id,
      error: { code: -32600, message: 'No session — send initialize first' },
    });
  }

  const { name: fullName, arguments: args } = params;
  const dotIdx = fullName.indexOf('.');
  if (dotIdx === -1) {
    return res.status(400).json({
      jsonrpc: '2.0', id,
      error: { code: -32602, message: `Tool name must be namespaced: <service>.<tool>` },
    });
  }

  const serviceName = fullName.slice(0, dotIdx);
  const toolName = fullName.slice(dotIdx + 1);
  const backend = session.backendSessions.get(serviceName);
  if (!backend) {
    return res.status(404).json({
      jsonrpc: '2.0', id,
      error: { code: -32602, message: `Unknown service: ${serviceName}` },
    });
  }

  console.log(`[aggregator] routing ${fullName} → ${serviceName}:${toolName}`);

  const resp = await fetch(`${backend.url}/mcp`, {
    method: 'POST',
    headers: backendHeaders(backend.sessionId),
    body: JSON.stringify({
      jsonrpc: '2.0',
      id,
      method: 'tools/call',
      params: { name: toolName, arguments: args },
    }),
  });

  const body = await parseResponse(resp);
  const clientSessionId = req.headers['mcp-session-id'];
  if (clientSessionId) res.setHeader('Mcp-Session-Id', clientSessionId);

  return res.json(body);
}

// ── Default handler — forward to all backends ─────────────────────────────
async function handleDefault(req, res, id, method, params) {
  const session = getSession(req);
  const targets = session
    ? [...session.backendSessions.entries()]
    : [...backends.entries()].map(([name, url]) => [name, { url, sessionId: null }]);

  const results = await Promise.allSettled(
    targets.map(async ([name, backend]) => {
      const resp = await fetch(`${backend.url}/mcp`, {
        method: 'POST',
        headers: backendHeaders(backend.sessionId),
        body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
      });
      return parseResponse(resp);
    })
  );

  // Return first successful response
  for (const result of results) {
    if (result.status === 'fulfilled') {
      const clientSessionId = req.headers['mcp-session-id'];
      if (clientSessionId) res.setHeader('Mcp-Session-Id', clientSessionId);
      return res.json(result.value);
    }
  }

  return res.status(502).json({
    jsonrpc: '2.0', id,
    error: { code: -32603, message: 'All backends failed' },
  });
}

// ── GET /mcp — SSE multiplexing from all backends ─────────────────────────
app.get('/mcp', (req, res) => {
  const session = getSession(req);
  if (!session) {
    return res.status(400).json({ error: 'No session — send initialize first' });
  }

  const clientSessionId = req.headers['mcp-session-id'];
  console.log(`[aggregator] SSE stream (session: ${clientSessionId})`);

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  if (clientSessionId) res.setHeader('Mcp-Session-Id', clientSessionId);
  res.flushHeaders();

  // Send initial keepalive
  res.write(': keepalive\n\n');

  // Connect to each backend's SSE endpoint
  const abortControllers = [];
  for (const [name, backend] of session.backendSessions) {
    const controller = new AbortController();
    abortControllers.push(controller);

    connectBackendSSE(name, backend, res, controller.signal);
  }

  // Keepalive every 30s
  const keepalive = setInterval(() => {
    res.write(': keepalive\n\n');
  }, 30000);

  // Cleanup on client disconnect
  req.on('close', () => {
    clearInterval(keepalive);
    for (const controller of abortControllers) {
      controller.abort();
    }
    console.log(`[aggregator] SSE client disconnected (session: ${clientSessionId})`);
  });
});

async function connectBackendSSE(name, backend, clientRes, signal) {
  try {
    const resp = await fetch(`${backend.url}/mcp`, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        ...(backend.sessionId ? { 'Mcp-Session-Id': backend.sessionId } : {}),
      },
      signal,
    });

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      clientRes.write(chunk);
    }
  } catch (err) {
    if (err.name !== 'AbortError') {
      console.warn(`[aggregator] SSE ${name} error:`, err.message);
    }
  }
}

// ── DELETE /mcp — Session termination ─────────────────────────────────────
app.delete('/mcp', async (req, res) => {
  const session = getSession(req);
  if (!session) {
    return res.status(404).json({ error: 'Session not found' });
  }

  const clientSessionId = req.headers['mcp-session-id'];
  console.log(`[aggregator] terminating session ${clientSessionId}`);

  // Send DELETE to all backends
  await Promise.allSettled(
    [...session.backendSessions.entries()].map(async ([name, backend]) => {
      try {
        await fetch(`${backend.url}/mcp`, {
          method: 'DELETE',
          headers: backend.sessionId ? { 'Mcp-Session-Id': backend.sessionId } : {},
        });
        console.log(`[aggregator] terminated ${name} session`);
      } catch (err) {
        console.warn(`[aggregator] DELETE ${name} failed:`, err.message);
      }
    })
  );

  sessions.delete(clientSessionId);
  return res.status(204).send();
});

// ── Health check ──────────────────────────────────────────────────────────
app.get('/health', (_req, res) => {
  res.json({
    status: 'healthy',
    service: 'mcp-aggregator',
    backends: [...backends.keys()],
    activeSessions: sessions.size,
  });
});

// ── Helpers ───────────────────────────────────────────────────────────────
function getSession(req) {
  const sessionId = req.headers['mcp-session-id'];
  return sessionId ? sessions.get(sessionId) : null;
}

// ── Start server ──────────────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log(`MCP Aggregator listening on port ${PORT}`);
  console.log(`  Backends: ${[...backends.keys()].join(', ')}`);
  console.log(`  POST   /mcp  — JSON-RPC (initialize, tools/list, tools/call)`);
  console.log(`  GET    /mcp  — SSE notification stream (multiplexed)`);
  console.log(`  DELETE /mcp  — Session termination`);
  console.log(`  GET    /health — Health check`);
});
