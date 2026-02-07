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
 * Root configuration structure
 */
export interface ServicesConfig {
  services: ServiceDefinition[];
}

/**
 * Get the absolute path to the config file
 */
function getConfigPath(): string {
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
