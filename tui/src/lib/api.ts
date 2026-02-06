/**
 * API client for interacting with the MCP Gateway and NPL Engine
 */

const GATEWAY_URL = process.env.GATEWAY_URL || "http://localhost:8000";
const NPL_URL = process.env.NPL_URL || "http://localhost:12000";
const KEYCLOAK_URL = process.env.KEYCLOAK_URL || "http://localhost:11000";
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || "mcpgateway";
const KEYCLOAK_CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || "mcpgateway";

// Cached admin token (set after login, lives in memory only)
let cachedToken: string | null = null;
let tokenExpiry: number = 0;
let adminUsername: string | null = null;
let adminPassword: string | null = null;

/**
 * Service info from the Gateway admin API
 */
export interface ServiceInfo {
  name: string;
  displayName: string;
  type: string;
  enabled: boolean;
  description: string;
  toolCount: number;
  tools: { name: string; description: string }[];
}

/**
 * Response from Gateway /admin/services endpoint
 */
export interface ServicesResponse {
  services: ServiceInfo[];
  totalServices: number;
  enabledServices: number;
  totalTools: number;
}

/**
 * Build authorization headers using the cached admin token.
 * Falls back to empty headers if not logged in (for health check etc.).
 */
async function authHeaders(): Promise<Record<string, string>> {
  try {
    const token = await getKeycloakToken();
    return { Authorization: `Bearer ${token}` };
  } catch {
    return {};
  }
}

/**
 * Fetch services list from Gateway admin API (requires admin JWT)
 */
export async function fetchServices(): Promise<ServicesResponse> {
  try {
    const headers = await authHeaders();
    const response = await fetch(`${GATEWAY_URL}/admin/services`, { headers });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    return await response.json();
  } catch (error) {
    console.error("Failed to fetch services from Gateway:", error);
    throw error;
  }
}

/**
 * Trigger config reload on Gateway (requires admin JWT)
 */
export async function reloadGatewayConfig(): Promise<void> {
  try {
    const headers = await authHeaders();
    const response = await fetch(`${GATEWAY_URL}/admin/services/reload`, {
      method: "POST",
      headers,
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
  } catch (error) {
    console.error("Failed to reload Gateway config:", error);
    throw error;
  }
}

/**
 * Check Gateway health
 */
export async function checkGatewayHealth(): Promise<boolean> {
  try {
    const response = await fetch(`${GATEWAY_URL}/health`);
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Call an MCP tool via the Gateway
 */
export async function callTool(
  toolName: string,
  args: Record<string, unknown>
): Promise<unknown> {
  const headers = await authHeaders();
  const response = await fetch(`${GATEWAY_URL}/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: Date.now(),
      method: "tools/call",
      params: {
        name: toolName,
        arguments: args,
      },
    }),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Set admin credentials for the TUI session (stored in memory only).
 * Must be called before any NPL operations.
 */
export function setAdminCredentials(username: string, password: string): void {
  adminUsername = username;
  adminPassword = password;
  // Invalidate cached token so next call uses new credentials
  cachedToken = null;
  tokenExpiry = 0;
}

/**
 * Check if admin credentials have been set for this session.
 */
export function hasAdminCredentials(): boolean {
  return adminUsername !== null && adminPassword !== null;
}

/**
 * Get access token from Keycloak using stored admin credentials.
 * Credentials must be set via setAdminCredentials() first (i.e., after login).
 */
export async function getKeycloakToken(): Promise<string> {
  if (!adminUsername || !adminPassword) {
    throw new Error("Admin credentials not set. Please log in first.");
  }

  // Return cached token if still valid (with 60s buffer)
  if (cachedToken && Date.now() < tokenExpiry - 60000) {
    return cachedToken;
  }

  const tokenUrl = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`;

  const response = await fetch(tokenUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: KEYCLOAK_CLIENT_ID,
      username: adminUsername,
      password: adminPassword,
    }),
  });

  if (!response.ok) {
    throw new Error(`Authentication failed: ${response.statusText}`);
  }

  const data = await response.json();
  cachedToken = data.access_token;
  tokenExpiry = Date.now() + data.expires_in * 1000;

  return cachedToken!;
}

/**
 * Validate admin credentials by attempting to get a token.
 * Returns true if the credentials are valid.
 */
export async function validateCredentials(
  username: string,
  password: string
): Promise<boolean> {
  const tokenUrl = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`;

  try {
    const response = await fetch(tokenUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        grant_type: "password",
        client_id: KEYCLOAK_CLIENT_ID,
        username,
        password,
      }),
    });

    return response.ok;
  } catch {
    return false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// NPL Engine API (admin operations - credentials required)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Find ServiceRegistry protocol instance in NPL.
 * Uses the correct Noumena engine API path: /npl/{package}/{Protocol}/
 */
async function findServiceRegistry(token: string): Promise<string | null> {
  try {
    const response = await fetch(
      `${NPL_URL}/npl/registry/ServiceRegistry/`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "application/json",
        },
      }
    );

    if (!response.ok) {
      return null;
    }

    const text = await response.text();
    // NPL engine may include control characters - strip them
    const clean = text.replace(/[\x00-\x1f]/g, "");
    const data = JSON.parse(clean);
    if (data.items && data.items.length > 0) {
      return data.items[0]["@id"] || null;
    }
  } catch {
    // ServiceRegistry may not exist
  }

  return null;
}

/**
 * Find ToolExecutionPolicy protocol instance in NPL.
 */
async function findToolExecutionPolicy(token: string): Promise<string | null> {
  try {
    const response = await fetch(
      `${NPL_URL}/npl/services/ToolExecutionPolicy/`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: "application/json",
        },
      }
    );

    if (!response.ok) {
      return null;
    }

    const text = await response.text();
    const clean = text.replace(/[\x00-\x1f]/g, "");
    const data = JSON.parse(clean);
    if (data.items && data.items.length > 0) {
      return data.items[0]["@id"] || null;
    }
  } catch {
    // ToolExecutionPolicy may not exist
  }

  return null;
}

/**
 * Bootstrap NPL: ensure ServiceRegistry and ToolExecutionPolicy exist,
 * and sync enabled services from services.yaml.
 * 
 * This is an admin operation - requires admin credentials.
 * Returns a summary of what was created/found.
 */
export async function bootstrapNpl(): Promise<{
  registryCreated: boolean;
  policyCreated: boolean;
  registryId: string;
  policyId: string;
  servicesEnabled: string[];
}> {
  const token = await getKeycloakToken();
  const gatewayUrl = process.env.GATEWAY_URL || "http://gateway:8080";
  
  // 1. Ensure ServiceRegistry exists
  let registryId = await findServiceRegistry(token);
  let registryCreated = false;

  if (!registryId) {
    const createResponse = await fetch(
      `${NPL_URL}/npl/registry/ServiceRegistry/`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ "@parties": {} }),
      }
    );

    if (!createResponse.ok) {
      const error = await createResponse.text();
      throw new Error(`Failed to create ServiceRegistry: ${error}`);
    }

    const createData = await createResponse.json();
    registryId = createData["@id"];
    registryCreated = true;
  }

  if (!registryId) {
    throw new Error("Failed to obtain ServiceRegistry ID");
  }

  // 2. Sync enabled services from services.yaml into ServiceRegistry
  const { loadConfig } = await import("./config.js");
  const config = loadConfig();
  const enabledServices = config.services.filter(s => s.enabled).map(s => s.name);
  
  for (const serviceName of enabledServices) {
    try {
      await fetch(
        `${NPL_URL}/npl/registry/ServiceRegistry/${registryId}/enableService`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ serviceName }),
        }
      );
    } catch {
      // Service may already be enabled - ignore
    }
  }

  // 3. Ensure ToolExecutionPolicy exists
  let policyId = await findToolExecutionPolicy(token);
  let policyCreated = false;

  if (!policyId) {
    const createPayload = {
      "@parties": {},  // Let rules.yml handle party assignment
      registry: registryId,
      policyTenantId: "default",
      policyGatewayUrl: gatewayUrl,
    };

    const createResponse = await fetch(
      `${NPL_URL}/npl/services/ToolExecutionPolicy/`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(createPayload),
      }
    );

    if (!createResponse.ok) {
      const error = await createResponse.text();
      throw new Error(`Failed to create ToolExecutionPolicy: ${error}`);
    }

    const createData = await createResponse.json();
    policyId = createData["@id"];
    policyCreated = true;
  }

  if (!policyId) {
    throw new Error("Failed to obtain ToolExecutionPolicy ID");
  }

  return {
    registryCreated,
    policyCreated,
    registryId,
    policyId,
    servicesEnabled: enabledServices,
  };
}

/**
 * Check if NPL has been bootstrapped (ServiceRegistry + ToolExecutionPolicy exist).
 */
export async function isNplBootstrapped(): Promise<boolean> {
  try {
    const token = await getKeycloakToken();
    const registryId = await findServiceRegistry(token);
    const policyId = await findToolExecutionPolicy(token);
    return registryId !== null && policyId !== null;
  } catch {
    return false;
  }
}

/**
 * Enable a service in NPL ServiceRegistry
 */
export async function enableServiceInNpl(serviceName: string): Promise<void> {
  const token = await getKeycloakToken();
  const registryId = await findServiceRegistry(token);

  if (!registryId) {
    throw new Error("ServiceRegistry not found in NPL. Run 'NPL Bootstrap' first.");
  }

  const response = await fetch(
    `${NPL_URL}/npl/registry/ServiceRegistry/${registryId}/enableService`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ serviceName }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to enable service in NPL: ${error}`);
  }
}

/**
 * Disable a service in NPL ServiceRegistry
 */
export async function disableServiceInNpl(serviceName: string): Promise<void> {
  const token = await getKeycloakToken();
  const registryId = await findServiceRegistry(token);

  if (!registryId) {
    throw new Error("ServiceRegistry not found in NPL. Run 'NPL Bootstrap' first.");
  }

  const response = await fetch(
    `${NPL_URL}/npl/registry/ServiceRegistry/${registryId}/disableService`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ serviceName }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to disable service in NPL: ${error}`);
  }
}

/**
 * Sync a service's enabled state with NPL ServiceRegistry
 */
export async function syncServiceWithNpl(
  serviceName: string,
  enabled: boolean
): Promise<void> {
  if (enabled) {
    await enableServiceInNpl(serviceName);
  } else {
    await disableServiceInNpl(serviceName);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Docker Hub MCP Server Discovery
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP server info from Docker Hub
 */
export interface McpServerInfo {
  name: string;
  fullName: string;
  description: string;
  pullCount: number;
  lastUpdated: string;
}

/**
 * Search Docker Hub for official MCP servers (mcp/* namespace)
 */
export async function discoverMcpServers(
  query?: string,
  limit: number = 50
): Promise<McpServerInfo[]> {
  const response = await fetch(
    `https://hub.docker.com/v2/repositories/mcp/?page_size=${limit}`
  );
  
  if (!response.ok) {
    throw new Error(`Docker Hub API error: ${response.status} ${response.statusText}`);
  }
  
  const data = await response.json();
  let servers: McpServerInfo[] = (data.results || []).map((repo: any) => ({
    name: repo.name,
    fullName: `mcp/${repo.name}`,
    description: repo.description || "No description",
    pullCount: repo.pull_count || 0,
    lastUpdated: repo.last_updated || "",
  }));
  
  // Filter by query if provided
  if (query) {
    const q = query.toLowerCase();
    servers = servers.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.description.toLowerCase().includes(q)
    );
  }
  
  // Sort by pull count (popularity)
  servers.sort((a, b) => b.pullCount - a.pullCount);
  
  return servers;
}

/**
 * Get detailed info for a specific MCP server from Docker Hub
 */
export async function getMcpServerDetails(
  serverName: string
): Promise<McpServerInfo | null> {
  try {
    const response = await fetch(
      `https://hub.docker.com/v2/repositories/mcp/${serverName}/`
    );
    
    if (!response.ok) {
      return null;
    }
    
    const repo = await response.json();
    return {
      name: repo.name,
      fullName: `mcp/${repo.name}`,
      description: repo.description || "No description",
      pullCount: repo.pull_count || 0,
      lastUpdated: repo.last_updated || "",
    };
  } catch (error) {
    return null;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Docker Container Management
// ─────────────────────────────────────────────────────────────────────────────

import { execSync, spawn, spawnSync } from "child_process";
import type { ToolDefinition, InputSchema, PropertySchema } from "./config.js";

/**
 * Container status info
 */
export interface ContainerStatus {
  running: boolean;
  containerId?: string;
  containerName?: string;
  status?: string;
}

/**
 * Check if a Docker container is running for a given MCP image
 */
export function getContainerStatus(imageName: string): ContainerStatus {
  try {
    // Check for running containers with this image
    const output = execSync(
      `docker ps --filter "ancestor=${imageName}" --format "{{.ID}}|{{.Names}}|{{.Status}}"`,
      { encoding: "utf-8", timeout: 5000 }
    ).trim();
    
    if (output) {
      const [id, name, status] = output.split("|");
      return {
        running: true,
        containerId: id,
        containerName: name,
        status: status,
      };
    }
    
    return { running: false };
  } catch (error) {
    return { running: false };
  }
}

/**
 * Start a Docker container for an MCP service
 * Returns the container ID if successful
 */
export function startContainer(
  imageName: string,
  containerName: string,
  options: { env?: Record<string, string>; ports?: string[] } = {}
): { success: boolean; containerId?: string; error?: string } {
  try {
    // Remove any existing stopped container with the same name
    try {
      execSync(`docker rm ${containerName} 2>/dev/null`, { encoding: "utf-8", timeout: 5000 });
    } catch {
      // Container doesn't exist or is running - that's fine
    }

    // Build docker run command
    // MCP STDIO servers need -i for stdin
    let cmd = `docker run -d -i --name ${containerName} --restart unless-stopped`;
    
    // Add environment variables
    if (options.env) {
      for (const [key, value] of Object.entries(options.env)) {
        cmd += ` -e ${key}="${value}"`;
      }
    }
    
    // Add port mappings
    if (options.ports) {
      for (const port of options.ports) {
        cmd += ` -p ${port}`;
      }
    }
    
    cmd += ` ${imageName}`;
    
    const containerId = execSync(cmd, { encoding: "utf-8", timeout: 30000 }).trim();
    
    return { success: true, containerId };
  } catch (error: any) {
    // Check if container already exists and is running
    if (error.message?.includes("is already in use")) {
      return { success: false, error: "Container is already running." };
    }
    return { success: false, error: error.message || "Failed to start container" };
  }
}

/**
 * Stop a running Docker container
 */
export function stopContainer(containerIdOrName: string): { success: boolean; error?: string } {
  try {
    execSync(`docker stop ${containerIdOrName}`, { encoding: "utf-8", timeout: 30000 });
    execSync(`docker rm ${containerIdOrName}`, { encoding: "utf-8", timeout: 10000 });
    return { success: true };
  } catch (error: any) {
    return { success: false, error: error.message || "Failed to stop container" };
  }
}

/**
 * Check if a Docker image exists locally
 */
export function imageExists(imageName: string): boolean {
  try {
    const output = execSync(`docker images -q ${imageName}`, { encoding: "utf-8", timeout: 5000 }).trim();
    return output.length > 0;
  } catch (error) {
    return false;
  }
}

/**
 * Pull a Docker image
 */
export function pullImage(imageName: string): { success: boolean; error?: string } {
  try {
    execSync(`docker pull ${imageName}`, { encoding: "utf-8", stdio: "inherit", timeout: 300000 });
    return { success: true };
  } catch (error: any) {
    return { success: false, error: error.message || "Failed to pull image" };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// MCP Tool Discovery
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Discovered tool from an MCP container
 */
export interface DiscoveredTool {
  name: string;
  description: string;
  inputSchema: {
    type: string;
    properties: Record<string, { type: string; description?: string }>;
    required?: string[];
  };
}

/**
 * Discover tools from an MCP container by running it and querying via STDIO.
 * Sends initialize + notifications/initialized + tools/list, then parses the response.
 */
export function discoverToolsFromContainer(imageName: string): {
  success: boolean;
  tools: DiscoveredTool[];
  serverInfo?: { name: string; version: string };
  error?: string;
} {
  try {
    // Build the MCP JSON-RPC messages to send via stdin
    const initMsg = JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2024-11-05",
        capabilities: {},
        clientInfo: { name: "noumena-tui-discovery", version: "1.0.0" },
      },
    });

    const initializedMsg = JSON.stringify({
      jsonrpc: "2.0",
      method: "notifications/initialized",
    });

    const toolsListMsg = JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/list",
      params: {},
    });

    // Use a shell script to send the messages with small delays
    // The container reads from stdin and writes responses to stdout
    const script = `(echo '${initMsg}'; sleep 1; echo '${initializedMsg}'; sleep 0.5; echo '${toolsListMsg}') | docker run -i --rm ${imageName} 2>/dev/null`;

    const output = execSync(script, {
      encoding: "utf-8",
      timeout: 30000,
      shell: "/bin/bash",
    }).trim();

    if (!output) {
      return { success: false, tools: [], error: "No output from container" };
    }

    // Parse the output - each line is a JSON-RPC response
    const lines = output.split("\n").filter((l) => l.trim().length > 0);

    let serverInfo: { name: string; version: string } | undefined;
    let tools: DiscoveredTool[] = [];

    for (const line of lines) {
      try {
        const response = JSON.parse(line);

        // Check for initialize response (id: 1)
        if (response.id === 1 && response.result?.serverInfo) {
          serverInfo = {
            name: response.result.serverInfo.name || "unknown",
            version: response.result.serverInfo.version || "unknown",
          };
        }

        // Check for tools/list response (id: 2)
        if (response.id === 2 && response.result?.tools) {
          tools = response.result.tools.map((tool: any) => ({
            name: tool.name,
            description: tool.description || "No description",
            inputSchema: {
              type: tool.inputSchema?.type || "object",
              properties: tool.inputSchema?.properties || {},
              required: tool.inputSchema?.required || [],
            },
          }));
        }
      } catch {
        // Skip lines that aren't valid JSON
        continue;
      }
    }

    return { success: true, tools, serverInfo };
  } catch (error: any) {
    const msg = error.message || "Unknown error";
    if (msg.includes("timed out")) {
      return { success: false, tools: [], error: "Container timed out (30s)" };
    }
    return { success: false, tools: [], error: msg.substring(0, 200) };
  }
}

/**
 * Convert discovered tools to ToolDefinition format for config
 */
export function discoveredToToolDefinitions(
  discovered: DiscoveredTool[],
  enabled: boolean = false
): ToolDefinition[] {
  return discovered.map((tool) => {
    // Convert properties to PropertySchema format
    const properties: Record<string, PropertySchema> = {};
    if (tool.inputSchema?.properties) {
      for (const [key, value] of Object.entries(tool.inputSchema.properties)) {
        properties[key] = {
          type: (value as any).type || "string",
          description: (value as any).description || "",
        };
      }
    }

    return {
      name: tool.name,
      description: (tool.description || "").replace(/\n/g, " ").trim(),
      inputSchema: {
        type: tool.inputSchema?.type || "object",
        properties,
        required: tool.inputSchema?.required || [],
      },
      enabled,
    };
  });
}
