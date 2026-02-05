/**
 * API client for interacting with the MCP Gateway and NPL Engine
 */

const GATEWAY_URL = process.env.GATEWAY_URL || "http://localhost:8000";
const NPL_URL = process.env.NPL_URL || "http://localhost:12000";
const KEYCLOAK_URL = process.env.KEYCLOAK_URL || "http://localhost:11000";
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || "mcpgateway";
const KEYCLOAK_CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || "mcpgateway";

// Cached token
let cachedToken: string | null = null;
let tokenExpiry: number = 0;

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
 * Fetch services list from Gateway admin API
 */
export async function fetchServices(): Promise<ServicesResponse> {
  try {
    const response = await fetch(`${GATEWAY_URL}/admin/services`);
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
 * Trigger config reload on Gateway
 */
export async function reloadGatewayConfig(): Promise<void> {
  try {
    const response = await fetch(`${GATEWAY_URL}/admin/services/reload`, {
      method: "POST",
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
  const response = await fetch(`${GATEWAY_URL}/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
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
 * Get access token from Keycloak
 */
export async function getKeycloakToken(
  username: string = "admin",
  password: string = "Welcome123"
): Promise<string> {
  // Return cached token if still valid
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
      username,
      password,
    }),
  });

  if (!response.ok) {
    throw new Error(`Failed to get Keycloak token: ${response.statusText}`);
  }

  const data = await response.json();
  cachedToken = data.access_token;
  tokenExpiry = Date.now() + data.expires_in * 1000;

  return cachedToken!;
}

/**
 * Find ServiceRegistry protocol instance in NPL
 */
async function findServiceRegistry(token: string): Promise<string | null> {
  const response = await fetch(
    `${NPL_URL}/api/protocols/service_registry.ServiceRegistry?limit=1`,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
      },
    }
  );

  if (!response.ok) {
    // ServiceRegistry may not exist - this is expected
    return null;
  }

  const data = await response.json();
  if (data.items && data.items.length > 0) {
    return data.items[0].id;
  }

  return null;
}

/**
 * Enable a service in NPL ServiceRegistry
 */
export async function enableServiceInNpl(serviceName: string): Promise<void> {
  const token = await getKeycloakToken();
  const registryId = await findServiceRegistry(token);

  if (!registryId) {
    throw new Error("ServiceRegistry not found in NPL");
  }

  const response = await fetch(
    `${NPL_URL}/api/protocols/service_registry.ServiceRegistry/${registryId}/enableService`,
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
    throw new Error("ServiceRegistry not found in NPL");
  }

  const response = await fetch(
    `${NPL_URL}/api/protocols/service_registry.ServiceRegistry/${registryId}/disableService`,
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
  try {
    if (enabled) {
      await enableServiceInNpl(serviceName);
    } else {
      await disableServiceInNpl(serviceName);
    }
  } catch (error) {
    // NPL sync is optional - ServiceRegistry may not exist
    throw error;
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

import { execSync, spawn } from "child_process";

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
    // Check if container already exists
    if (error.message?.includes("is already in use")) {
      return { success: false, error: "Container already exists. Stop it first." };
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
