import { parse, stringify } from "yaml";
import { readFileSync, writeFileSync, existsSync } from "fs";
import { resolve } from "path";

/**
 * Tool input schema property definition
 */
export interface PropertySchema {
  type: string;
  description: string;
}

/**
 * Tool input schema definition
 */
export interface InputSchema {
  type: string;
  properties: Record<string, PropertySchema>;
  required: string[];
}

/**
 * Tool definition
 */
export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: InputSchema;
  enabled?: boolean; // undefined means enabled (default)
}

/**
 * Service definition from services.yaml
 */
export interface ServiceDefinition {
  name: string;
  displayName: string;
  type: "MCP_STDIO" | "MCP_HTTP" | "DIRECT_REST";
  enabled: boolean;
  command?: string;
  args?: string[];
  endpoint?: string;
  baseUrl?: string;
  requiresCredentials: boolean;
  vaultPath?: string;
  description: string;
  tools: ToolDefinition[];
}

/**
 * User tool access definition
 */
export interface UserToolAccess {
  userId: string;
  keycloakId?: string;
  displayName?: string;
  createdAt?: string;
  tools: Record<string, string[]>; // serviceName -> tool names (["*"] for all)
  vaultPaths?: Record<string, string>; // serviceName -> vault path (future)
}

/**
 * Default template for new users
 */
export interface DefaultToolTemplate {
  enabled: boolean;
  description: string;
  tools: Record<string, string[]>; // serviceName -> tool names
}

/**
 * User access control section
 */
export interface UserAccessConfig {
  default_template?: DefaultToolTemplate;
  users: UserToolAccess[];
}

/**
 * Root configuration structure
 */
export interface ServicesConfig {
  services: ServiceDefinition[];
  user_access?: UserAccessConfig;
}

/**
 * Get the absolute path to the config file
 */
export function getConfigPath(): string {
  const envPath = process.env.SERVICES_CONFIG_PATH;
  if (envPath && existsSync(envPath)) {
    return envPath;
  }
  
  // Try multiple paths
  const paths = [
    resolve(process.cwd(), "configs/services.yaml"),
    resolve(process.cwd(), "../configs/services.yaml"),
    "/Users/juerg/development/noumena-mcp-gateway/configs/services.yaml",
  ];
  
  for (const p of paths) {
    if (existsSync(p)) {
      return p;
    }
  }
  
  // Default fallback
  return paths[0];
}

/**
 * Load services configuration from YAML file
 */
export function loadConfig(): ServicesConfig {
  const configPath = getConfigPath();
  
  if (!existsSync(configPath)) {
    console.error(`Config file not found: ${configPath}`);
    return { services: [] };
  }
  
  try {
    const content = readFileSync(configPath, "utf-8");
    const config = parse(content) as ServicesConfig;
    return config;
  } catch (error) {
    console.error(`Failed to load config: ${error}`);
    return { services: [] };
  }
}

/**
 * Save services configuration to YAML file
 */
export function saveConfig(config: ServicesConfig): boolean {
  const configPath = getConfigPath();
  
  try {
    const content = stringify(config, {
      lineWidth: 0, // Disable line wrapping
      defaultStringType: "QUOTE_DOUBLE",
      defaultKeyType: "PLAIN",
    });
    
    // Add header comment
    const header = `# Dynamic MCP Service Configuration
# This file defines all upstream services and their tools.
# The Gateway reads this config for tool listing and upstream routing.

`;
    
    writeFileSync(configPath, header + content, "utf-8");
    return true;
  } catch (error) {
    console.error(`Failed to save config: ${error}`);
    return false;
  }
}

/**
 * Get a service by name
 */
export function getService(name: string): ServiceDefinition | undefined {
  const config = loadConfig();
  return config.services.find((s) => s.name === name);
}

/**
 * Update a service's enabled status
 */
export function setServiceEnabled(name: string, enabled: boolean): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === name);
  
  if (!service) {
    console.error(`Service not found: ${name}`);
    return false;
  }
  
  service.enabled = enabled;
  return saveConfig(config);
}

/**
 * Add a new service
 */
export function addService(service: ServiceDefinition): boolean {
  const config = loadConfig();
  
  // Check if service already exists
  if (config.services.some((s) => s.name === service.name)) {
    console.error(`Service already exists: ${service.name}`);
    return false;
  }
  
  config.services.push(service);
  return saveConfig(config);
}

/**
 * Remove a service
 */
export function removeService(name: string): boolean {
  const config = loadConfig();
  const index = config.services.findIndex((s) => s.name === name);
  
  if (index === -1) {
    console.error(`Service not found: ${name}`);
    return false;
  }
  
  config.services.splice(index, 1);
  return saveConfig(config);
}

/**
 * Update an existing service
 */
export function updateService(name: string, updates: Partial<ServiceDefinition>): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === name);
  
  if (!service) {
    console.error(`Service not found: ${name}`);
    return false;
  }
  
  Object.assign(service, updates);
  return saveConfig(config);
}

/**
 * Toggle a tool's enabled status within a service
 */
export function setToolEnabled(serviceName: string, toolName: string, enabled: boolean): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === serviceName);
  
  if (!service) {
    console.error(`Service not found: ${serviceName}`);
    return false;
  }
  
  const tool = service.tools.find((t) => t.name === toolName);
  if (!tool) {
    console.error(`Tool not found: ${toolName}`);
    return false;
  }
  
  tool.enabled = enabled;
  return saveConfig(config);
}

/**
 * Add a tool to a service
 */
export function addTool(serviceName: string, tool: ToolDefinition): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === serviceName);
  
  if (!service) {
    console.error(`Service not found: ${serviceName}`);
    return false;
  }
  
  // Check if tool already exists
  if (service.tools.some((t) => t.name === tool.name)) {
    console.error(`Tool already exists: ${tool.name}`);
    return false;
  }
  
  service.tools.push(tool);
  return saveConfig(config);
}

/**
 * Replace all tools for a service (used after tool discovery)
 */
export function updateServiceTools(serviceName: string, tools: ToolDefinition[]): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === serviceName);
  
  if (!service) {
    console.error(`Service not found: ${serviceName}`);
    return false;
  }
  
  service.tools = tools;
  return saveConfig(config);
}

/**
 * Remove a tool from a service
 */
export function removeTool(serviceName: string, toolName: string): boolean {
  const config = loadConfig();
  const service = config.services.find((s) => s.name === serviceName);
  
  if (!service) {
    console.error(`Service not found: ${serviceName}`);
    return false;
  }
  
  const index = service.tools.findIndex((t) => t.name === toolName);
  if (index === -1) {
    console.error(`Tool not found: ${toolName}`);
    return false;
  }
  
  service.tools.splice(index, 1);
  return saveConfig(config);
}

// ============================================================================
// User Access Management
// ============================================================================

/**
 * Get all users
 */
export function getAllUsers(): UserToolAccess[] {
  const config = loadConfig();
  return config.user_access?.users || [];
}

/**
 * Get a specific user by userId
 */
export function getUser(userId: string): UserToolAccess | undefined {
  const users = getAllUsers();
  return users.find((u) => u.userId === userId);
}

/**
 * Add a new user
 */
export function addUser(user: UserToolAccess): boolean {
  const config = loadConfig();
  
  // Initialize user_access if it doesn't exist
  if (!config.user_access) {
    config.user_access = { users: [] };
  }
  
  // Check if user already exists
  if (config.user_access.users.some((u) => u.userId === user.userId)) {
    console.error(`User already exists: ${user.userId}`);
    return false;
  }
  
  config.user_access.users.push(user);
  return saveConfig(config);
}

/**
 * Update an existing user
 */
export function updateUser(userId: string, updates: Partial<UserToolAccess>): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const userIndex = config.user_access.users.findIndex((u) => u.userId === userId);
  if (userIndex === -1) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  // Merge updates
  config.user_access.users[userIndex] = {
    ...config.user_access.users[userIndex],
    ...updates,
  };
  
  return saveConfig(config);
}

/**
 * Remove a user
 */
export function removeUser(userId: string): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const userIndex = config.user_access.users.findIndex((u) => u.userId === userId);
  if (userIndex === -1) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  config.user_access.users.splice(userIndex, 1);
  return saveConfig(config);
}

/**
 * Grant a tool to a user
 */
export function grantToolToUser(userId: string, serviceName: string, toolName: string): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const user = config.user_access.users.find((u) => u.userId === userId);
  if (!user) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  // Initialize service tools if not exists
  if (!user.tools[serviceName]) {
    user.tools[serviceName] = [];
  }
  
  // Add tool if not already present
  if (!user.tools[serviceName].includes(toolName)) {
    user.tools[serviceName].push(toolName);
  }
  
  return saveConfig(config);
}

/**
 * Revoke a tool from a user
 */
export function revokeToolFromUser(userId: string, serviceName: string, toolName: string): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const user = config.user_access.users.find((u) => u.userId === userId);
  if (!user) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  if (!user.tools[serviceName]) {
    return true; // Nothing to revoke
  }
  
  // Remove tool
  user.tools[serviceName] = user.tools[serviceName].filter((t) => t !== toolName);
  
  // Remove service if no tools left
  if (user.tools[serviceName].length === 0) {
    delete user.tools[serviceName];
  }
  
  return saveConfig(config);
}

/**
 * Grant all tools for a service to a user
 */
export function grantAllToolsToUser(userId: string, serviceName: string): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const user = config.user_access.users.find((u) => u.userId === userId);
  if (!user) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  // Set wildcard
  user.tools[serviceName] = ["*"];
  
  return saveConfig(config);
}

/**
 * Revoke all access to a service for a user
 */
export function revokeServiceFromUser(userId: string, serviceName: string): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    console.error("No user_access section in config");
    return false;
  }
  
  const user = config.user_access.users.find((u) => u.userId === userId);
  if (!user) {
    console.error(`User not found: ${userId}`);
    return false;
  }
  
  delete user.tools[serviceName];
  
  return saveConfig(config);
}

/**
 * Get default tool template
 */
export function getDefaultToolTemplate(): DefaultToolTemplate | undefined {
  const config = loadConfig();
  return config.user_access?.default_template;
}

/**
 * Update default tool template
 */
export function updateDefaultToolTemplate(template: DefaultToolTemplate): boolean {
  const config = loadConfig();
  
  if (!config.user_access) {
    config.user_access = { users: [] };
  }
  
  config.user_access.default_template = template;
  return saveConfig(config);
}

// ====================================================================
// CREDENTIAL CONFIGURATION MANAGEMENT
// ====================================================================

interface CredentialMapping {
  vault_path: string;
  injection: {
    type: string;
    mapping: Record<string, string>;
  };
}

interface CredentialConfig {
  mode: string;
  tenant: string;
  credentials: Record<string, CredentialMapping>;
  service_defaults: Record<string, string>;
  default_credential?: CredentialMapping;
}

const getCredentialsConfigPath = () => {
  const servicesPath = getConfigPath();
  return servicesPath.replace("services.yaml", "credentials.yaml");
};

/**
 * Load credentials configuration
 */
export function loadCredentialsConfig(): CredentialConfig {
  try {
    const content = readFileSync(getCredentialsConfigPath(), "utf-8");
    return parse(content) as CredentialConfig;
  } catch (error) {
    // Return default structure if file doesn't exist
    return {
      mode: "simple",
      tenant: "default",
      credentials: {},
      service_defaults: {},
    };
  }
}

/**
 * Save credentials configuration
 */
export function saveCredentialsConfig(config: CredentialConfig): boolean {
  try {
    const yamlContent = stringify(config);
    writeFileSync(getCredentialsConfigPath(), yamlContent, "utf-8");
    return true;
  } catch (error) {
    console.error("Failed to save credentials config:", error);
    return false;
  }
}

/**
 * Add or update a credential mapping
 */
export function addCredentialMapping(
  credentialName: string,
  vaultPath: string,
  injectionType: string,
  fieldMapping: Record<string, string>
): boolean {
  const config = loadCredentialsConfig();
  
  config.credentials[credentialName] = {
    vault_path: vaultPath,
    injection: {
      type: injectionType,
      mapping: fieldMapping,
    },
  };
  
  return saveCredentialsConfig(config);
}

/**
 * Set service default credential
 */
export function setServiceCredential(serviceName: string, credentialName: string): boolean {
  const config = loadCredentialsConfig();
  config.service_defaults[serviceName] = credentialName;
  return saveCredentialsConfig(config);
}

/**
 * Get credential mapping for a service
 */
export function getServiceCredential(serviceName: string): string | null {
  const config = loadCredentialsConfig();
  return config.service_defaults[serviceName] || null;
}

/**
 * Check if service has credentials configured
 */
export function hasCredentials(serviceName: string): boolean {
  return getServiceCredential(serviceName) !== null;
}
