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
}

/**
 * User access control section
 */
export interface UserAccessConfig {
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
      lineWidth: 0,
      defaultStringType: "QUOTE_DOUBLE",
      defaultKeyType: "PLAIN",
    });

    const header = `# Dynamic MCP Service Configuration
# This file is an import/export format for bulk configuration.
# PolicyStore is the runtime source of truth.

`;

    writeFileSync(configPath, header + content, "utf-8");
    return true;
  } catch (error) {
    console.error(`Failed to save config: ${error}`);
    return false;
  }
}
