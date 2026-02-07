/**
 * API client for interacting with the MCP Gateway and NPL Engine.
 *
 * V2 Architecture:
 *   - ServiceRegistry: tracks which services exist
 *   - ToolPolicy (per-service): governs tool-level access
 *   - No more ToolExecutionPolicy (legacy, kept for migration)
 *   - No more executor references
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
 * Call an MCP tool via the Gateway (using namespaced tool names)
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
 */
export function setAdminCredentials(username: string, password: string): void {
  adminUsername = username;
  adminPassword = password;
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
 */
export async function getKeycloakToken(): Promise<string> {
  if (!adminUsername || !adminPassword) {
    throw new Error("Admin credentials not set. Please log in first.");
  }

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
// NPL Engine API (admin operations)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Find ServiceRegistry protocol instance in NPL.
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

    if (!response.ok) return null;

    const text = await response.text();
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
 * Find ToolPolicy instance for a specific service.
 */
async function findToolPolicyForService(
  token: string,
  serviceName: string
): Promise<string | null> {
  try {
    const response = await fetch(`${NPL_URL}/npl/services/ToolPolicy/`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
      },
    });

    if (!response.ok) return null;

    const text = await response.text();
    const clean = text.replace(/[\x00-\x1f]/g, "");
    const data = JSON.parse(clean);
    if (data.items) {
      for (const item of data.items) {
        if (item.policyServiceName === serviceName) {
          return item["@id"] || null;
        }
      }
    }
  } catch {
    // ToolPolicy may not exist
  }

  return null;
}

/**
 * Create a ToolPolicy instance for a service.
 */
async function createToolPolicy(
  token: string,
  serviceName: string
): Promise<string | null> {
  const createPayload = {
    "@parties": {},
    policyServiceName: serviceName,
  };

  const response = await fetch(`${NPL_URL}/npl/services/ToolPolicy/`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(createPayload),
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to create ToolPolicy for '${serviceName}': ${error}`);
  }

  const data = await response.json();
  return data["@id"] || null;
}

/**
 * Enable a tool in a ToolPolicy instance.
 */
async function enableToolInPolicy(
  token: string,
  policyId: string,
  toolName: string
): Promise<void> {
  const response = await fetch(
    `${NPL_URL}/npl/services/ToolPolicy/${policyId}/enableTool`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ toolName }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to enable tool '${toolName}': ${error}`);
  }
}

/**
 * Enable all tools in a ToolPolicy instance.
 */
async function enableAllToolsInPolicy(
  token: string,
  policyId: string,
  toolNames: string[]
): Promise<void> {
  const response = await fetch(
    `${NPL_URL}/npl/services/ToolPolicy/${policyId}/enableAllTools`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ toolNames }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to enable tools: ${error}`);
  }
}

/**
 * Bootstrap NPL: ensure ServiceRegistry, ToolPolicy, and UserRegistry/UserToolAccess instances exist.
 *
 * V3 Architecture:
 *   1. Create ServiceRegistry (if needed)
 *   2. Sync enabled services from services.yaml
 *   3. Create ToolPolicy instances per enabled service (if needed)
 *   4. Enable tools in each ToolPolicy based on services.yaml
 *   5. Create UserRegistry (if needed) - NEW
 *   6. Sync users from services.yaml user_access section - NEW
 *   7. Create UserToolAccess per user and grant tools - NEW
 */
export async function bootstrapNpl(): Promise<{
  registryCreated: boolean;
  policiesCreated: string[];
  registryId: string;
  servicesEnabled: string[];
  userRegistryCreated: boolean;
  usersCreated: string[];
}> {
  const token = await getKeycloakToken();

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
  const enabledServices = config.services.filter((s) => s.enabled);
  const enabledServiceNames = enabledServices.map((s) => s.name);

  for (const serviceName of enabledServiceNames) {
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
      // Service may already be enabled
    }
  }

  // 3. Create ToolPolicy instances per enabled service
  const policiesCreated: string[] = [];

  for (const service of enabledServices) {
    let policyId = await findToolPolicyForService(token, service.name);

    if (!policyId) {
      policyId = await createToolPolicy(token, service.name);
      if (policyId) {
        policiesCreated.push(service.name);
      }
    }

    // 4. Enable tools based on services.yaml
    if (policyId) {
      const enabledTools = service.tools
        .filter((t) => t.enabled)
        .map((t) => t.name);

      if (enabledTools.length > 0) {
        try {
          await enableAllToolsInPolicy(token, policyId, enabledTools);
        } catch {
          // Try one by one as fallback
          for (const toolName of enabledTools) {
            try {
              await enableToolInPolicy(token, policyId, toolName);
            } catch {
              // Tool may already be enabled
            }
          }
        }
      }
    }
  }

  // 5. Ensure UserRegistry exists
  let userRegistryId = await findUserRegistry(token);
  let userRegistryCreated = false;

  if (!userRegistryId) {
    userRegistryId = await createUserRegistry(token);
    userRegistryCreated = true;
  }

  // 6. Sync users from services.yaml user_access section
  const usersCreated: string[] = [];
  const users = config.user_access?.users || [];

  for (const user of users) {
    try {
      // Register user in UserRegistry (if not already)
      try {
        await registerUserInNpl(user.userId);
      } catch {
        // User may already be registered
      }

      // Sync tool access
      await syncUserAccessToNpl(user.userId, user.tools);
      usersCreated.push(user.userId);
    } catch (error) {
      console.error(`Failed to sync user ${user.userId}:`, error);
      // Continue with other users
    }
  }

  return {
    registryCreated,
    policiesCreated,
    registryId,
    servicesEnabled: enabledServiceNames,
    userRegistryCreated,
    usersCreated,
  };
}

/**
 * Check if NPL has been bootstrapped (ServiceRegistry exists).
 */
export async function isNplBootstrapped(): Promise<boolean> {
  try {
    const token = await getKeycloakToken();
    const registryId = await findServiceRegistry(token);
    return registryId !== null;
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
    throw new Error(
      `Docker Hub API error: ${response.status} ${response.statusText}`
    );
  }

  const data = await response.json();
  let servers: McpServerInfo[] = (data.results || []).map((repo: any) => ({
    name: repo.name,
    fullName: `mcp/${repo.name}`,
    description: repo.description || "No description",
    pullCount: repo.pull_count || 0,
    lastUpdated: repo.last_updated || "",
  }));

  if (query) {
    const q = query.toLowerCase();
    servers = servers.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.description.toLowerCase().includes(q)
    );
  }

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
 */
export function startContainer(
  imageName: string,
  containerName: string,
  options: { env?: Record<string, string>; ports?: string[] } = {}
): { success: boolean; containerId?: string; error?: string } {
  try {
    try {
      execSync(`docker rm ${containerName} 2>/dev/null`, {
        encoding: "utf-8",
        timeout: 5000,
      });
    } catch {
      // Container doesn't exist or is running
    }

    let cmd = `docker run -d -i --name ${containerName} --restart unless-stopped`;

    if (options.env) {
      for (const [key, value] of Object.entries(options.env)) {
        cmd += ` -e ${key}="${value}"`;
      }
    }

    if (options.ports) {
      for (const port of options.ports) {
        cmd += ` -p ${port}`;
      }
    }

    cmd += ` ${imageName}`;

    const containerId = execSync(cmd, {
      encoding: "utf-8",
      timeout: 30000,
    }).trim();

    return { success: true, containerId };
  } catch (error: any) {
    if (error.message?.includes("is already in use")) {
      return { success: false, error: "Container is already running." };
    }
    return {
      success: false,
      error: error.message || "Failed to start container",
    };
  }
}

/**
 * Stop a running Docker container
 */
export function stopContainer(
  containerIdOrName: string
): { success: boolean; error?: string } {
  try {
    execSync(`docker stop ${containerIdOrName}`, {
      encoding: "utf-8",
      timeout: 30000,
    });
    execSync(`docker rm ${containerIdOrName}`, {
      encoding: "utf-8",
      timeout: 10000,
    });
    return { success: true };
  } catch (error: any) {
    return {
      success: false,
      error: error.message || "Failed to stop container",
    };
  }
}

/**
 * Check if a Docker image exists locally
 */
export function imageExists(imageName: string): boolean {
  try {
    const output = execSync(`docker images -q ${imageName}`, {
      encoding: "utf-8",
      timeout: 5000,
    }).trim();
    return output.length > 0;
  } catch (error) {
    return false;
  }
}

/**
 * Pull a Docker image
 */
export function pullImage(
  imageName: string
): { success: boolean; error?: string } {
  try {
    execSync(`docker pull ${imageName}`, {
      encoding: "utf-8",
      stdio: "inherit",
      timeout: 300000,
    });
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
 */
export function discoverToolsFromContainer(imageName: string): {
  success: boolean;
  tools: DiscoveredTool[];
  serverInfo?: { name: string; version: string };
  error?: string;
} {
  try {
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

    const script = `(echo '${initMsg}'; sleep 1; echo '${initializedMsg}'; sleep 0.5; echo '${toolsListMsg}') | docker run -i --rm ${imageName} 2>/dev/null`;

    const output = execSync(script, {
      encoding: "utf-8",
      timeout: 30000,
      shell: "/bin/bash",
    }).trim();

    if (!output) {
      return { success: false, tools: [], error: "No output from container" };
    }

    const lines = output.split("\n").filter((l) => l.trim().length > 0);

    let serverInfo: { name: string; version: string } | undefined;
    let tools: DiscoveredTool[] = [];

    for (const line of lines) {
      try {
        const response = JSON.parse(line);

        if (response.id === 1 && response.result?.serverInfo) {
          serverInfo = {
            name: response.result.serverInfo.name || "unknown",
            version: response.result.serverInfo.version || "unknown",
          };
        }

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
        continue;
      }
    }

    return { success: true, tools, serverInfo };
  } catch (error: any) {
    const msg = error.message || "Unknown error";
    if (msg.includes("timed out")) {
      return {
        success: false,
        tools: [],
        error: "Container timed out (30s)",
      };
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

// ============================================================================
// Keycloak User Management
// ============================================================================

// The admin user in the mcpgateway realm has realm-management roles,
// so the regular login token works for Keycloak Admin REST API calls too.
// No separate master realm authentication needed.

/**
 * Keycloak user representation
 */
export interface KeycloakUser {
  id?: string;
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  attributes?: Record<string, string[]>;
  createdTimestamp?: number;
}

/**
 * Get a token for the Keycloak Admin REST API.
 * The admin user in the mcpgateway realm has realm-management roles,
 * so the regular login token works for admin API calls too.
 * No separate master realm authentication needed.
 */
async function getKeycloakAdminToken(): Promise<string> {
  if (!cachedToken) {
    throw new Error("Not logged in. Please log in first.");
  }
  return cachedToken;
}

/**
 * Get Keycloak admin API base URL
 */
function getKeycloakAdminUrl(): string {
  return `${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}`;
}

/**
 * List all users in Keycloak
 */
export async function listKeycloakUsers(): Promise<KeycloakUser[]> {
  const token = await getKeycloakAdminToken();
  const response = await fetch(`${getKeycloakAdminUrl()}/users?max=100`, {
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to list users (${response.status}): ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Get a specific user by ID
 */
export async function getKeycloakUser(userId: string): Promise<KeycloakUser> {
  const token = await getKeycloakAdminToken();
  const response = await fetch(`${getKeycloakAdminUrl()}/users/${userId}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to get user: ${response.statusText}`);
  }

  return await response.json();
}

/**
 * Create a new user in Keycloak
 * Returns the created user's ID
 */
export async function createKeycloakUser(
  email: string,
  username: string,
  firstName?: string,
  lastName?: string,
  initialPassword?: string
): Promise<string> {
  const token = await getKeycloakAdminToken();
  
  const userData: Record<string, unknown> = {
    username,
    email,
    firstName: firstName || undefined,
    lastName: lastName || undefined,
    enabled: true,
    emailVerified: false,
  };

  // If password provided, include credentials inline
  if (initialPassword) {
    userData.credentials = [{
      type: "password",
      value: initialPassword,
      temporary: false,
    }];
  }

  const response = await fetch(`${getKeycloakAdminUrl()}/users`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(userData),
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to create user (${response.status}): ${error}`);
  }

  // Get the user ID from Location header
  const location = response.headers.get("Location");
  if (location) {
    return location.split("/").pop()!;
  }

  // Fallback: search for the user we just created
  const users = await searchKeycloakUsersByEmail(email);
  if (users.length > 0 && users[0].id) {
    return users[0].id;
  }

  return "unknown";
}

/**
 * Set or reset a user's password
 */
export async function setKeycloakUserPassword(
  userId: string,
  password: string,
  temporary: boolean = false
): Promise<void> {
  const token = await getKeycloakAdminToken();
  
  const response = await fetch(`${getKeycloakAdminUrl()}/users/${userId}/reset-password`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      type: "password",
      value: password,
      temporary,
    }),
  });

  if (!response.ok) {
    throw new Error(`Failed to set password: ${response.statusText}`);
  }
}

/**
 * Delete a user from Keycloak
 */
export async function deleteKeycloakUser(userId: string): Promise<void> {
  const token = await getKeycloakAdminToken();
  
  const response = await fetch(`${getKeycloakAdminUrl()}/users/${userId}`, {
    method: "DELETE",
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to delete user: ${response.statusText}`);
  }
}

/**
 * Search for users by email
 */
export async function searchKeycloakUsersByEmail(email: string): Promise<KeycloakUser[]> {
  const token = await getKeycloakAdminToken();
  const response = await fetch(`${getKeycloakAdminUrl()}/users?email=${encodeURIComponent(email)}&exact=true`, {
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to search users: ${response.statusText}`);
  }

  return await response.json();
}

// ============================================================================
// NPL User Access Management
// ============================================================================

/**
 * Find UserRegistry protocol instance
 */
async function findUserRegistry(token: string): Promise<string | null> {
  try {
    const response = await fetch(
      `${NPL_URL}/npl/users/UserRegistry/?fields=@id`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      }
    );

    if (response.ok) {
      const data = await response.json();
      if (data.length > 0) {
        return data[0]["@id"];
      }
    }
  } catch (error) {
    console.error("Error finding UserRegistry:", error);
  }

  return null;
}

/**
 * Create UserRegistry protocol instance
 */
async function createUserRegistry(token: string): Promise<string> {
  const response = await fetch(
    `${NPL_URL}/npl/users/UserRegistry/`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ "@parties": {} }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to create UserRegistry: ${error}`);
  }

  const data = await response.json();
  return data["@id"];
}

/**
 * Register a user in NPL UserRegistry
 */
export async function registerUserInNpl(userId: string): Promise<void> {
  const token = await getKeycloakToken();
  let registryId = await findUserRegistry(token);

  if (!registryId) {
    registryId = await createUserRegistry(token);
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserRegistry/${registryId}/registerUser`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ userId }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to register user in NPL: ${error}`);
  }
}

/**
 * Remove a user from NPL UserRegistry
 */
export async function removeUserFromNpl(userId: string): Promise<void> {
  const token = await getKeycloakToken();
  const registryId = await findUserRegistry(token);

  if (!registryId) {
    throw new Error("UserRegistry not found");
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserRegistry/${registryId}/removeUser`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ userId }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to remove user from NPL: ${error}`);
  }
}

/**
 * Find UserToolAccess protocol instance for a specific user
 */
async function findUserToolAccess(token: string, userId: string): Promise<string | null> {
  try {
    const response = await fetch(
      `${NPL_URL}/npl/users/UserToolAccess/?userId=${encodeURIComponent(userId)}&fields=@id`,
      {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      }
    );

    if (response.ok) {
      const data = await response.json();
      if (data.length > 0) {
        return data[0]["@id"];
      }
    }
  } catch (error) {
    console.error("Error finding UserToolAccess:", error);
  }

  return null;
}

/**
 * Create UserToolAccess protocol instance for a user
 */
async function createUserToolAccess(token: string, userId: string): Promise<string> {
  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        "@parties": {},
        userId,
      }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to create UserToolAccess: ${error}`);
  }

  const data = await response.json();
  return data["@id"];
}

/**
 * Grant a tool to a user in NPL
 */
export async function grantToolInNpl(
  userId: string,
  serviceName: string,
  toolName: string
): Promise<void> {
  const token = await getKeycloakToken();
  let accessId = await findUserToolAccess(token, userId);

  if (!accessId) {
    accessId = await createUserToolAccess(token, userId);
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/${accessId}/grantTool`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ serviceName, toolName }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to grant tool: ${error}`);
  }
}

/**
 * Revoke a tool from a user in NPL
 */
export async function revokeToolInNpl(
  userId: string,
  serviceName: string,
  toolName: string
): Promise<void> {
  const token = await getKeycloakToken();
  const accessId = await findUserToolAccess(token, userId);

  if (!accessId) {
    return; // User has no access protocol, nothing to revoke
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/${accessId}/revokeTool`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ serviceName, toolName }),
    }
  );

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to revoke tool: ${error}`);
  }
}

/**
 * Grant all tools for a service to a user in NPL
 */
export async function grantAllToolsForServiceInNpl(
  userId: string,
  serviceName: string
): Promise<void> {
  const token = await getKeycloakToken();
  let accessId = await findUserToolAccess(token, userId);

  if (!accessId) {
    accessId = await createUserToolAccess(token, userId);
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/${accessId}/grantAllToolsForService`,
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
    throw new Error(`Failed to grant all tools: ${error}`);
  }
}

/**
 * Revoke all access to a service for a user in NPL
 */
export async function revokeServiceInNpl(
  userId: string,
  serviceName: string
): Promise<void> {
  const token = await getKeycloakToken();
  const accessId = await findUserToolAccess(token, userId);

  if (!accessId) {
    return; // User has no access protocol, nothing to revoke
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/${accessId}/revokeServiceAccess`,
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
    throw new Error(`Failed to revoke service access: ${error}`);
  }
}

/**
 * Get all services and tools a user has access to from NPL
 */
export async function getUserAccessFromNpl(userId: string): Promise<Record<string, string[]>> {
  const token = await getKeycloakToken();
  const accessId = await findUserToolAccess(token, userId);

  if (!accessId) {
    return {}; // No access protocol = no access
  }

  const response = await fetch(
    `${NPL_URL}/npl/users/UserToolAccess/${accessId}/getAccessList`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({}),
    }
  );

  if (!response.ok) {
    throw new Error(`Failed to get user access: ${response.statusText}`);
  }

  const data = await response.json();
  
  // Convert NPL List<ServiceToolAccess> to JS object
  // NPL returns: [{ serviceName: "duckduckgo", allowedTools: ["search"] }, ...]
  const result: Record<string, string[]> = {};
  if (Array.isArray(data)) {
    for (const entry of data) {
      result[entry.serviceName] = Array.from(entry.allowedTools || []);
    }
  }
  return result;
}

/**
 * Sync user tool access from services.yaml to NPL
 * Creates UserToolAccess protocol and grants all configured tools
 */
export async function syncUserAccessToNpl(userId: string, tools: Record<string, string[]>): Promise<void> {
  const token = await getKeycloakToken();
  
  // Ensure user is registered
  await registerUserInNpl(userId);
  
  // Get or create UserToolAccess protocol
  let accessId = await findUserToolAccess(token, userId);
  if (!accessId) {
    accessId = await createUserToolAccess(token, userId);
  }

  // Grant each tool
  for (const [serviceName, toolNames] of Object.entries(tools)) {
    if (toolNames.includes("*")) {
      // Grant all tools for service
      await grantAllToolsForServiceInNpl(userId, serviceName);
    } else {
      // Grant individual tools
      for (const toolName of toolNames) {
        await grantToolInNpl(userId, serviceName, toolName);
      }
    }
  }
}
