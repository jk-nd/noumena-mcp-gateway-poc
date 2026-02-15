/**
 * API client for interacting with the MCP Gateway, NPL PolicyStore, and Keycloak.
 *
 * PolicyStore-first architecture:
 *   - PolicyStore singleton is the single source of truth for all policy data
 *   - All reads/writes go through callPolicyStore()
 *   - services.yaml is only used for bulk import/export
 */

import { execSync } from "child_process";
import type { ToolDefinition, InputSchema, PropertySchema } from "./config.js";

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

// Cached PolicyStore singleton ID
let policyStoreId: string | null = null;

// Cached ApprovalPolicy instance ID
let approvalPolicyId: string | null = null;

// ============================================================================
// PolicyStore types (mirrors NPL structs)
// ============================================================================

export interface ServiceEntry {
  serviceName: string;
  enabled: boolean;
  enabledTools: string[];
  suspended: boolean;
  metadata: Record<string, string>;
}

export interface GrantEntry {
  serviceName: string;
  allowedTools: string[];
}

export interface PolicyData {
  services: Record<string, ServiceEntry>;
  grants: Record<string, GrantEntry[]>;
  revokedSubjects: string[];
  contextualRoutes: Record<string, Record<string, unknown>>;
  securityPolicy: string;
}

export interface PendingApproval {
  approvalId: string;
  lookupKey: string;
  callerIdentity: string;
  toolName: string;
  verb: string;
  labels: string;
  argumentDigest: string;
  status: string;
  reason: string;
  decidedBy: string;
}

// ============================================================================
// Gateway Admin API (unchanged)
// ============================================================================

export interface ServiceInfo {
  name: string;
  displayName: string;
  type: string;
  enabled: boolean;
  description: string;
  toolCount: number;
  tools: { name: string; description: string }[];
}

export interface ServicesResponse {
  services: ServiceInfo[];
  totalServices: number;
  enabledServices: number;
  totalTools: number;
}

async function authHeaders(): Promise<Record<string, string>> {
  try {
    const token = await getKeycloakToken();
    return { Authorization: `Bearer ${token}` };
  } catch {
    return {};
  }
}

export async function fetchServices(): Promise<ServicesResponse> {
  const headers = await authHeaders();
  const response = await fetch(`${GATEWAY_URL}/admin/services`, { headers });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }
  return await response.json();
}

export async function reloadGatewayConfig(): Promise<void> {
  const headers = await authHeaders();
  const response = await fetch(`${GATEWAY_URL}/admin/services/reload`, {
    method: "POST",
    headers,
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }
}

export async function checkGatewayHealth(): Promise<boolean> {
  try {
    const response = await fetch(`${GATEWAY_URL}/health`);
    return response.ok;
  } catch {
    return false;
  }
}

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
      params: { name: toolName, arguments: args },
    }),
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }
  return await response.json();
}

// ============================================================================
// Keycloak Auth (unchanged)
// ============================================================================

export function setAdminCredentials(username: string, password: string): void {
  adminUsername = username;
  adminPassword = password;
  cachedToken = null;
  tokenExpiry = 0;
  policyStoreId = null; // Reset PolicyStore cache on re-login
  approvalPolicyId = null;
}

export function hasAdminCredentials(): boolean {
  return adminUsername !== null && adminPassword !== null;
}

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
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
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

export async function validateCredentials(
  username: string,
  password: string
): Promise<boolean> {
  const tokenUrl = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`;
  try {
    const response = await fetch(tokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
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

// ============================================================================
// PolicyStore Core
// ============================================================================

/**
 * Find or create the PolicyStore singleton. Caches the ID.
 */
export async function ensurePolicyStore(): Promise<string> {
  if (policyStoreId) return policyStoreId;

  const token = await getKeycloakToken();

  // List existing instances
  const listResponse = await fetch(`${NPL_URL}/npl/policy/PolicyStore/`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
  });

  if (listResponse.ok) {
    const text = await listResponse.text();
    const clean = text.replace(/[\x00-\x1f]/g, "");
    const data = JSON.parse(clean);
    if (data.items && data.items.length > 0) {
      policyStoreId = data.items[0]["@id"];
      if (policyStoreId) return policyStoreId;
    }
  }

  // Create singleton
  const createResponse = await fetch(`${NPL_URL}/npl/policy/PolicyStore/`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ "@parties": {} }),
  });

  if (!createResponse.ok) {
    const error = await createResponse.text();
    throw new Error(`Failed to create PolicyStore: ${error}`);
  }

  const createData = await createResponse.json();
  policyStoreId = createData["@id"];
  if (!policyStoreId) throw new Error("PolicyStore created but no ID returned");
  return policyStoreId;
}

/**
 * Call a PolicyStore action. All PolicyStore operations go through this.
 */
async function callPolicyStore(
  action: string,
  body: Record<string, unknown> = {}
): Promise<Response> {
  const storeId = await ensurePolicyStore();
  const token = await getKeycloakToken();
  return fetch(`${NPL_URL}/npl/policy/PolicyStore/${storeId}/${action}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
}

// ============================================================================
// PolicyStore Reads
// ============================================================================

/**
 * Get all policy data in a single call.
 */
export async function getPolicyData(): Promise<PolicyData> {
  const response = await callPolicyStore("getPolicyData");
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`getPolicyData failed: ${error}`);
  }
  return await response.json();
}

// ============================================================================
// PolicyStore Security Policy Actions
// ============================================================================

/**
 * Set the security policy (JSON-serialized merged policy).
 * Called by the apply-security-policy flow after YAML → merge → validate.
 */
export async function setSecurityPolicy(policyJson: string): Promise<void> {
  const response = await callPolicyStore("setSecurityPolicy", { policyJson });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`setSecurityPolicy failed: ${error}`);
  }
}

/**
 * Clear the security policy (revert to no security policy).
 */
export async function clearSecurityPolicy(): Promise<void> {
  const response = await callPolicyStore("clearSecurityPolicy", {});
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`clearSecurityPolicy failed: ${error}`);
  }
}

// ============================================================================
// ApprovalPolicy Core
// ============================================================================

/**
 * Find or create the ApprovalPolicy singleton. Caches the ID.
 */
export async function ensureApprovalPolicy(): Promise<string> {
  if (approvalPolicyId) return approvalPolicyId;

  const token = await getKeycloakToken();

  // List existing instances
  const listResponse = await fetch(`${NPL_URL}/npl/policies/ApprovalPolicy/`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
  });

  if (listResponse.ok) {
    const text = await listResponse.text();
    const clean = text.replace(/[\x00-\x1f]/g, "");
    const data = JSON.parse(clean);
    if (data.items && data.items.length > 0) {
      approvalPolicyId = data.items[0]["@id"];
      if (approvalPolicyId) return approvalPolicyId;
    }
  }

  // Create singleton
  const createResponse = await fetch(`${NPL_URL}/npl/policies/ApprovalPolicy/`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ "@parties": {} }),
  });

  if (!createResponse.ok) {
    const error = await createResponse.text();
    throw new Error(`Failed to create ApprovalPolicy: ${error}`);
  }

  const createData = await createResponse.json();
  approvalPolicyId = createData["@id"];
  if (!approvalPolicyId) throw new Error("ApprovalPolicy created but no ID returned");
  return approvalPolicyId;
}

/**
 * Call an ApprovalPolicy action.
 */
async function callApprovalPolicy(
  action: string,
  body: Record<string, unknown> = {}
): Promise<Response> {
  const instanceId = await ensureApprovalPolicy();
  const token = await getKeycloakToken();
  return fetch(`${NPL_URL}/npl/policies/ApprovalPolicy/${instanceId}/${action}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
}

// ============================================================================
// ApprovalPolicy Actions
// ============================================================================

export async function getPendingApprovals(): Promise<PendingApproval[]> {
  const response = await callApprovalPolicy("getPendingApprovals");
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`getPendingApprovals failed: ${error}`);
  }
  return await response.json();
}

export async function getAllApprovals(): Promise<PendingApproval[]> {
  const response = await callApprovalPolicy("getAllApprovals");
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`getAllApprovals failed: ${error}`);
  }
  return await response.json();
}

export async function approveRequest(approvalId: string): Promise<string> {
  const response = await callApprovalPolicy("approve", { approvalId });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`approve failed: ${error}`);
  }
  return await response.json();
}

export async function denyRequest(approvalId: string, reason: string): Promise<string> {
  const response = await callApprovalPolicy("deny", { approvalId, reason });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`deny failed: ${error}`);
  }
  return await response.json();
}

export async function clearResolvedApprovals(): Promise<void> {
  const response = await callApprovalPolicy("clearResolved");
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`clearResolved failed: ${error}`);
  }
}

// ============================================================================
// PolicyStore Catalog Actions
// ============================================================================

export async function registerService(serviceName: string): Promise<void> {
  const response = await callPolicyStore("registerService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`registerService failed: ${error}`);
  }
}

export async function enableService(serviceName: string): Promise<void> {
  const response = await callPolicyStore("enableService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`enableService failed: ${error}`);
  }
}

export async function disableService(serviceName: string): Promise<void> {
  const response = await callPolicyStore("disableService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`disableService failed: ${error}`);
  }
}

export async function suspendService(serviceName: string): Promise<void> {
  const response = await callPolicyStore("suspendService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`suspendService failed: ${error}`);
  }
}

export async function resumeService(serviceName: string): Promise<void> {
  const response = await callPolicyStore("resumeService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`resumeService failed: ${error}`);
  }
}

export async function removeServiceFromPolicyStore(serviceName: string): Promise<void> {
  const response = await callPolicyStore("removeService", { serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`removeService failed: ${error}`);
  }
}

export async function enableTool(serviceName: string, toolName: string): Promise<void> {
  const response = await callPolicyStore("enableTool", { serviceName, toolName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`enableTool failed: ${error}`);
  }
}

export async function disableTool(serviceName: string, toolName: string): Promise<void> {
  const response = await callPolicyStore("disableTool", { serviceName, toolName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`disableTool failed: ${error}`);
  }
}

export async function setServiceMetadata(
  serviceName: string,
  key: string,
  value: string
): Promise<void> {
  const response = await callPolicyStore("setServiceMetadata", { serviceName, key, value });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`setServiceMetadata failed: ${error}`);
  }
}

// ============================================================================
// PolicyStore Grant Actions
// ============================================================================

export async function grantTool(
  subjectId: string,
  serviceName: string,
  toolName: string
): Promise<void> {
  const response = await callPolicyStore("grantTool", { subjectId, serviceName, toolName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`grantTool failed: ${error}`);
  }
}

export async function grantAllToolsForService(
  subjectId: string,
  serviceName: string
): Promise<void> {
  const response = await callPolicyStore("grantAllToolsForService", { subjectId, serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`grantAllToolsForService failed: ${error}`);
  }
}

export async function revokeTool(
  subjectId: string,
  serviceName: string,
  toolName: string
): Promise<void> {
  const response = await callPolicyStore("revokeTool", { subjectId, serviceName, toolName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`revokeTool failed: ${error}`);
  }
}

export async function revokeServiceAccess(
  subjectId: string,
  serviceName: string
): Promise<void> {
  const response = await callPolicyStore("revokeServiceAccess", { subjectId, serviceName });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`revokeServiceAccess failed: ${error}`);
  }
}

export async function revokeSubject(subjectId: string): Promise<void> {
  const response = await callPolicyStore("revokeSubject", { subjectId });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`revokeSubject failed: ${error}`);
  }
}

export async function reinstateSubject(subjectId: string): Promise<void> {
  const response = await callPolicyStore("reinstateSubject", { subjectId });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`reinstateSubject failed: ${error}`);
  }
}

// ============================================================================
// Import / Export (YAML ↔ PolicyStore reconciliation)
// ============================================================================

/**
 * Import services.yaml into PolicyStore.
 * Reads YAML, registers/enables services, sets metadata, enables tools, syncs grants.
 */
export async function importFromYaml(): Promise<{
  servicesImported: string[];
  grantsImported: string[];
}> {
  const { loadConfig } = await import("./config.js");
  const config = loadConfig();

  const servicesImported: string[] = [];
  const grantsImported: string[] = [];

  // Get current PolicyStore state to know what already exists
  let existingData: PolicyData;
  try {
    existingData = await getPolicyData();
  } catch {
    existingData = { services: {}, grants: {}, revokedSubjects: [], contextualRoutes: {}, securityPolicy: "" };
  }

  // Import services
  for (const svc of config.services) {
    // Register if not exists
    if (!existingData.services[svc.name]) {
      await registerService(svc.name);
    }

    // Set metadata
    if (svc.displayName) await setServiceMetadata(svc.name, "displayName", svc.displayName);
    if (svc.type) await setServiceMetadata(svc.name, "type", svc.type);
    if (svc.command) await setServiceMetadata(svc.name, "command", svc.command);
    if (svc.args?.length) await setServiceMetadata(svc.name, "args", JSON.stringify(svc.args));
    if (svc.description) await setServiceMetadata(svc.name, "description", svc.description);
    if (svc.endpoint) await setServiceMetadata(svc.name, "endpoint", svc.endpoint);
    if (svc.baseUrl) await setServiceMetadata(svc.name, "baseUrl", svc.baseUrl);

    // Enable/disable
    if (svc.enabled) {
      await enableService(svc.name);
    } else {
      await disableService(svc.name);
    }

    // Enable tools
    for (const tool of svc.tools) {
      if (tool.enabled !== false) {
        await enableTool(svc.name, tool.name);
      }
    }

    servicesImported.push(svc.name);
  }

  // Import user grants
  const users = config.user_access?.users || [];
  for (const user of users) {
    for (const [serviceName, toolNames] of Object.entries(user.tools)) {
      if (toolNames.includes("*")) {
        await grantAllToolsForService(user.userId, serviceName);
      } else {
        for (const toolName of toolNames) {
          await grantTool(user.userId, serviceName, toolName);
        }
      }
    }
    grantsImported.push(user.userId);
  }

  return { servicesImported, grantsImported };
}

/**
 * Export PolicyStore data to services.yaml.
 * Reads PolicyStore, writes YAML file.
 */
export async function exportToYaml(): Promise<void> {
  const { saveConfig } = await import("./config.js");
  const data = await getPolicyData();

  const services = Object.values(data.services).map((entry) => ({
    name: entry.serviceName,
    displayName: entry.metadata?.displayName || entry.serviceName,
    type: (entry.metadata?.type as any) || "MCP_STDIO",
    enabled: entry.enabled,
    command: entry.metadata?.command || undefined,
    args: entry.metadata?.args ? JSON.parse(entry.metadata.args) : undefined,
    endpoint: entry.metadata?.endpoint || undefined,
    baseUrl: entry.metadata?.baseUrl || undefined,
    requiresCredentials: false,
    description: entry.metadata?.description || "",
    tools: entry.enabledTools.map((toolName: string) => ({
      name: toolName,
      description: "",
      inputSchema: { type: "object", properties: {}, required: [] },
      enabled: true,
    })),
  }));

  const users = Object.entries(data.grants).map(([userId, grantEntries]) => ({
    userId,
    tools: Object.fromEntries(
      grantEntries.map((g) => [g.serviceName, Array.from(g.allowedTools)])
    ),
  }));

  saveConfig({
    services,
    user_access: users.length > 0 ? { users } : undefined,
  });
}

// ============================================================================
// Docker Hub MCP Server Discovery (unchanged)
// ============================================================================

export interface McpServerInfo {
  name: string;
  fullName: string;
  description: string;
  pullCount: number;
  lastUpdated: string;
}

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

export async function getMcpServerDetails(
  serverName: string
): Promise<McpServerInfo | null> {
  try {
    const response = await fetch(
      `https://hub.docker.com/v2/repositories/mcp/${serverName}/`
    );
    if (!response.ok) return null;

    const repo = await response.json();
    return {
      name: repo.name,
      fullName: `mcp/${repo.name}`,
      description: repo.description || "No description",
      pullCount: repo.pull_count || 0,
      lastUpdated: repo.last_updated || "",
    };
  } catch {
    return null;
  }
}

// ============================================================================
// Docker Container Management (unchanged)
// ============================================================================

export interface ContainerStatus {
  running: boolean;
  containerId?: string;
  containerName?: string;
  status?: string;
}

export function getContainerStatus(imageName: string): ContainerStatus {
  try {
    const output = execSync(
      `docker ps --filter "ancestor=${imageName}" --format "{{.ID}}|{{.Names}}|{{.Status}}"`,
      { encoding: "utf-8", timeout: 5000 }
    ).trim();

    if (output) {
      const [id, name, status] = output.split("|");
      return { running: true, containerId: id, containerName: name, status };
    }
    return { running: false };
  } catch {
    return { running: false };
  }
}

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

    const containerId = execSync(cmd, { encoding: "utf-8", timeout: 30000 }).trim();
    return { success: true, containerId };
  } catch (error: any) {
    if (error.message?.includes("is already in use")) {
      return { success: false, error: "Container is already running." };
    }
    return { success: false, error: error.message || "Failed to start container" };
  }
}

export function stopContainer(
  containerIdOrName: string
): { success: boolean; error?: string } {
  try {
    execSync(`docker stop ${containerIdOrName}`, { encoding: "utf-8", timeout: 30000 });
    execSync(`docker rm ${containerIdOrName}`, { encoding: "utf-8", timeout: 10000 });
    return { success: true };
  } catch (error: any) {
    return { success: false, error: error.message || "Failed to stop container" };
  }
}

export function imageExists(imageName: string): boolean {
  try {
    const output = execSync(`docker images -q ${imageName}`, {
      encoding: "utf-8",
      timeout: 5000,
    }).trim();
    return output.length > 0;
  } catch {
    return false;
  }
}

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

// ============================================================================
// MCP Tool Discovery (unchanged)
// ============================================================================

export interface DiscoveredTool {
  name: string;
  description: string;
  inputSchema: {
    type: string;
    properties: Record<string, { type: string; description?: string }>;
    required?: string[];
  };
}

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
      return { success: false, tools: [], error: "Container timed out (30s)" };
    }
    return { success: false, tools: [], error: msg.substring(0, 200) };
  }
}

export function discoverToolsFromCommand(
  command: string,
  args: string[],
  env?: Record<string, string>
): {
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

    const escapedArgs = args.map((a) => `'${a.replace(/'/g, "'\\''")}'`).join(" ");

    const macScript = `
OUTFILE=$(mktemp /tmp/mcp-out.XXXXXX)
FIFO=$(mktemp -u /tmp/mcp-fifo.XXXXXX)
mkfifo "$FIFO"

${command} ${escapedArgs} < "$FIFO" > "$OUTFILE" 2>/dev/null &
SERVER_PID=$!

(
  sleep 3
  printf '%s\\n' '${initMsg}'
  sleep 2
  printf '%s\\n' '${initializedMsg}'
  sleep 1
  printf '%s\\n' '${toolsListMsg}'
  sleep 5
) > "$FIFO" &
FEED_PID=$!

ELAPSED=0
while [ $ELAPSED -lt 45 ]; do
  if grep -q '"id":2' "$OUTFILE" 2>/dev/null; then
    break
  fi
  sleep 1
  ELAPSED=$((ELAPSED + 1))
done

kill $FEED_PID 2>/dev/null
kill $SERVER_PID 2>/dev/null
wait $FEED_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
cat "$OUTFILE"
rm -f "$OUTFILE" "$FIFO"
`;

    const output = execSync(macScript, {
      encoding: "utf-8",
      timeout: 60000,
      shell: "/bin/bash",
      env: { ...process.env, ...env },
    }).trim();

    if (!output) {
      return { success: false, tools: [], error: "No output from command" };
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
      return { success: false, tools: [], error: "Command timed out (60s)" };
    }
    return { success: false, tools: [], error: msg.substring(0, 200) };
  }
}

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
// Keycloak User Management (unchanged)
// ============================================================================

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

async function getKeycloakAdminToken(): Promise<string> {
  if (!cachedToken) {
    throw new Error("Not logged in. Please log in first.");
  }
  return cachedToken;
}

function getKeycloakAdminUrl(): string {
  return `${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}`;
}

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

  const location = response.headers.get("Location");
  if (location) {
    return location.split("/").pop()!;
  }

  const users = await searchKeycloakUsersByEmail(email);
  if (users.length > 0 && users[0].id) {
    return users[0].id;
  }

  return "unknown";
}

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
    body: JSON.stringify({ type: "password", value: password, temporary }),
  });
  if (!response.ok) {
    throw new Error(`Failed to set password: ${response.statusText}`);
  }
}

export async function deleteKeycloakUser(userId: string): Promise<void> {
  const token = await getKeycloakAdminToken();
  const response = await fetch(`${getKeycloakAdminUrl()}/users/${userId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    throw new Error(`Failed to delete user: ${response.statusText}`);
  }
}

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
