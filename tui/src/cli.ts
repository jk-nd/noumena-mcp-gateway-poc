#!/usr/bin/env node
/**
 * NOUMENA MCP Gateway Wizard
 *
 * Interactive CLI for managing MCP Gateway services.
 * Uses @clack/prompts for a beautiful terminal experience.
 *
 * PolicyStore-first architecture:
 *   - PolicyStore is the single runtime source of truth
 *   - services.yaml is only used for bulk import/export
 *   - No credential management (parked)
 *   - Grants-only user model (no registration step)
 */

import * as p from "@clack/prompts";
import chalk from "chalk";
import { execSync } from "child_process";
import { readFileSync } from "fs";
import * as path from "path";
import {
  getConfigPath,
  loadConfig,
  type ServiceDefinition,
} from "./lib/config.js";
import {
  checkGatewayHealth,
  reloadGatewayConfig,
  ensurePolicyStore,
  getPolicyData,
  registerService,
  enableService,
  disableService,
  suspendService,
  resumeService,
  removeServiceFromPolicyStore,
  enableTool,
  disableTool,
  setServiceMetadata,
  grantTool,
  grantAllToolsForService,
  revokeTool,
  revokeServiceAccess,
  revokeSubject,
  reinstateSubject,
  importFromYaml,
  exportToYaml,
  discoverMcpServers,
  imageExists,
  discoverToolsFromContainer,
  discoverToolsFromCommand,
  discoveredToToolDefinitions,
  setAdminCredentials,
  validateCredentials,
  listKeycloakUsers,
  createKeycloakUser,
  deleteKeycloakUser,
  type KeycloakUser,
  type PolicyData,
  type ServiceEntry,
  type GrantEntry,
} from "./lib/api.js";

// Noumena color palette - optimized for dark terminal backgrounds
const noumena = {
  purple: chalk.hex("#A78BFA"),
  purpleDim: chalk.hex("#8B5CF6"),
  accent: chalk.hex("#60A5FA"),
  accentBright: chalk.hex("#93C5FD"),
  success: chalk.hex("#34D399"),
  warning: chalk.hex("#FBBF24"),
  error: chalk.hex("#F87171"),
  text: chalk.hex("#F3F4F6"),
  textDim: chalk.hex("#D1D5DB"),
  gray: chalk.hex("#9CA3AF"),
  grayDim: chalk.hex("#6B7280"),
};

function showHeader() {
  console.log();
  console.log(noumena.purple("  ◆ NOUMENA MCP Gateway Wizard"));
  console.log();
}

// ============================================================================
// Admin Login (unchanged)
// ============================================================================

async function adminLogin(): Promise<boolean> {
  console.log();
  console.log(noumena.purple("  Admin Login"));
  console.log(noumena.textDim("  Credentials are stored in memory only for this session."));
  console.log();

  const envUser = process.env.MCP_ADMIN_USER;
  const envPass = process.env.MCP_ADMIN_PASSWORD;

  if (envUser && envPass) {
    const s = p.spinner();
    s.start("Authenticating with environment credentials...");
    const valid = await validateCredentials(envUser, envPass);
    if (valid) {
      setAdminCredentials(envUser, envPass);
      s.stop(noumena.success(`Authenticated as ${envUser}`));
      return true;
    } else {
      s.stop(noumena.purpleDim("Environment credentials invalid - prompting for login"));
    }
  }

  for (let attempt = 0; attempt < 3; attempt++) {
    const username = await p.text({
      message: "Username:",
      initialValue: "admin",
      validate: (v) => (!v || v.trim().length === 0 ? "Username is required" : undefined),
    });
    if (p.isCancel(username)) return false;

    const password = await p.password({
      message: "Password:",
      validate: (v) => (!v || v.length === 0 ? "Password is required" : undefined),
    });
    if (p.isCancel(password)) return false;

    const s = p.spinner();
    s.start("Authenticating...");

    const valid = await validateCredentials(String(username).trim(), String(password));
    if (valid) {
      setAdminCredentials(String(username).trim(), String(password));
      s.stop(noumena.success(`Authenticated as ${username}`));
      return true;
    } else {
      s.stop(noumena.purpleDim("Invalid credentials"));
      if (attempt < 2) p.log.warn("Please try again.");
    }
  }

  p.log.error("Too many failed attempts.");
  return false;
}

// ============================================================================
// Helper: get Docker image from metadata
// ============================================================================

function getDockerImageFromMetadata(entry: ServiceEntry): string | null {
  const command = entry.metadata?.command;
  if (!command) return null;
  if (command.includes("docker run")) {
    const mcpMatch = command.match(/mcp\/[\w-]+/);
    if (mcpMatch) return mcpMatch[0];
    const match = command.match(/docker\s+run\s+[^]*?\s+([\w\-\/.]+)\s*$/);
    if (match) return match[1];
  }
  return null;
}

function getDockerImageName(service: ServiceDefinition): string | null {
  if (service.type !== "MCP_STDIO" || !service.command) return null;
  if (service.command.includes("docker run")) {
    const match = service.command.match(/docker\s+run\s+[^]*?\s+([\w\-\/.]+)\s*$/);
    if (match) return match[1];
    const mcpMatch = service.command.match(/mcp\/[\w-]+/);
    if (mcpMatch) return mcpMatch[0];
  }
  return null;
}

function isContainerRunning(imageName: string): boolean {
  try {
    const result = execSync(`docker ps --filter ancestor=${imageName} --format "{{.ID}}"`, {
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "ignore"],
    });
    return result.trim().length > 0;
  } catch {
    return false;
  }
}

function getContainerId(imageName: string): string | null {
  try {
    const result = execSync(`docker ps --filter ancestor=${imageName} --format "{{.ID}}"`, {
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "ignore"],
    });
    const id = result.trim().split("\n")[0];
    return id || null;
  } catch {
    return null;
  }
}

function startDockerContainer(imageName: string, name: string): { success: boolean; error?: string; command?: string } {
  try {
    try {
      const existing = execSync(`docker ps -a --filter name=^${name}$ --format "{{.ID}}"`, {
        encoding: "utf-8",
        stdio: ["pipe", "pipe", "ignore"],
      });
      if (existing.trim()) {
        execSync(`docker rm -f ${name}`, { stdio: "pipe" });
      }
    } catch {
      // Container doesn't exist
    }
    const cmd = `docker run -d --name ${name} ${imageName}`;
    execSync(cmd, { encoding: "utf-8", stdio: "pipe" });
    return { success: true, command: cmd };
  } catch (err: any) {
    return { success: false, error: err.message || "Failed to start container" };
  }
}

function stopDockerContainer(containerId: string): boolean {
  try {
    execSync(`docker stop ${containerId}`, { stdio: "pipe" });
    execSync(`docker rm ${containerId}`, { stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
}

function getTypeLabel(type: string): string {
  switch (type) {
    case "MCP_STDIO": return "STDIO";
    case "MCP_HTTP": return "HTTP";
    case "DIRECT_REST": return "REST";
    default: return type;
  }
}

// ============================================================================
// Main Menu — reads from PolicyStore
// ============================================================================

async function mainMenu(): Promise<boolean> {
  let policyData: PolicyData;
  try {
    policyData = await getPolicyData();
  } catch {
    policyData = { services: {}, grants: {}, revokedSubjects: [], contextualRoutes: {} };
  }

  const services = Object.values(policyData.services);
  const gatewayConnected = await checkGatewayHealth();

  // Count grants (unique subjects)
  const grantCount = Object.keys(policyData.grants).length;

  const enabledCount = services.filter((s) => s.enabled).length;

  console.clear();
  showHeader();

  // Status bar
  const gatewayStatus = gatewayConnected
    ? noumena.success("● Connected")
    : noumena.grayDim("○ Disconnected");

  console.log(
    noumena.textDim("  Gateway: ") + gatewayStatus +
    noumena.textDim("  |  Services: ") + noumena.text(`${enabledCount}/${services.length}`) +
    noumena.textDim("  |  Grants: ") + noumena.text(`${grantCount}`)
  );
  console.log(
    noumena.textDim("  ") +
    noumena.success("●") + noumena.textDim(" enabled  ") +
    noumena.grayDim("–") + noumena.textDim(" disabled  ") +
    noumena.warning("⏸") + noumena.textDim(" suspended  ") +
    noumena.success("■") + noumena.textDim(" image ready  ") +
    noumena.grayDim("·") + noumena.textDim(" not pulled")
  );
  console.log();

  // Build service options from PolicyStore
  const serviceOptions: { value: string; label: string; hint?: string }[] = services.map((entry) => {
    let statusIcon: string;
    if (entry.suspended) {
      statusIcon = noumena.warning("⏸");
    } else if (entry.enabled) {
      statusIcon = noumena.success("●");
    } else {
      statusIcon = noumena.grayDim("–");
    }

    const imageName = getDockerImageFromMetadata(entry);
    let containerIcon = "";
    if (imageName) {
      const pulled = imageExists(imageName);
      const type = entry.metadata?.type || "MCP_STDIO";
      if (type !== "MCP_STDIO") {
        const running = isContainerRunning(imageName);
        containerIcon = running ? noumena.success(" ▶") : pulled ? noumena.success(" ■") : noumena.grayDim(" ·");
      } else {
        containerIcon = pulled ? noumena.success(" ■") : noumena.grayDim(" ·");
      }
    }

    const displayName = entry.metadata?.displayName || entry.serviceName;
    const type = entry.metadata?.type || "MCP_STDIO";
    const toolCount = entry.enabledTools.length;

    return {
      value: `service:${entry.serviceName}`,
      label: `${statusIcon}${containerIcon}  ${displayName}`,
      hint: `${getTypeLabel(type)} | ${toolCount} tools`,
    };
  });

  if (services.length === 0) {
    serviceOptions.push({
      value: "---no-services",
      label: noumena.textDim("  No services configured yet"),
      hint: "",
    });
  }

  const options: { value: string; label: string; hint?: string }[] = [
    { value: "---hdr-gw", label: noumena.purple("── Gateway Settings ──"), hint: "" },
    ...serviceOptions,
    { value: "search", label: noumena.accent("  + Search Docker Hub"), hint: "Find and add MCP servers" },
    { value: "custom", label: noumena.accent("  + Add MCP service"), hint: "Templates, NPM, or Docker" },

    { value: "---hdr-users", label: noumena.purple("── User Management ──"), hint: "" },
    { value: "users", label: "  Manage users & tool access", hint: `${grantCount} subject(s) with grants` },

    { value: "---hdr-sys", label: noumena.purple("── System ──"), hint: "" },
    { value: "config", label: "  Import / Export", hint: "YAML import, export, backups" },
    { value: "quit", label: "  Quit", hint: "" },
  ];

  const action = await p.select({ message: "Select:", options });

  if (p.isCancel(action) || action === "quit") return false;
  if (typeof action === "string" && action.startsWith("---")) return true;

  if (typeof action === "string" && action.startsWith("service:")) {
    const serviceName = action.replace("service:", "");
    const entry = policyData.services[serviceName];
    if (entry) await serviceActionsFlow(entry, policyData);
  } else if (action === "search") {
    await searchDockerHubFlow();
  } else if (action === "custom") {
    await addCustomImageFlow();
  } else if (action === "users") {
    await userManagementFlow();
  } else if (action === "config") {
    await configurationManagerFlow();
  }

  return true;
}

// ============================================================================
// Service Actions — reads/writes PolicyStore directly
// ============================================================================

async function serviceActionsFlow(entry: ServiceEntry, policyData: PolicyData): Promise<void> {
  const imageName = getDockerImageFromMetadata(entry);
  const isDocker = imageName !== null;
  const hasImage = isDocker ? imageExists(imageName) : false;
  const type = entry.metadata?.type || "MCP_STDIO";
  const containerRunning = isDocker && hasImage && type !== "MCP_STDIO" ? isContainerRunning(imageName) : false;
  const displayName = entry.metadata?.displayName || entry.serviceName;
  const command = entry.metadata?.command;

  console.log();
  console.log(noumena.purple(`  ${displayName}`));
  console.log(noumena.textDim(`  Type: ${getTypeLabel(type)}`));
  console.log(
    noumena.textDim(`  Status: `) +
    (entry.suspended ? noumena.warning("Suspended") :
     entry.enabled ? noumena.success("Enabled") : noumena.grayDim("Disabled"))
  );
  if (isDocker) {
    console.log(noumena.textDim(`  Image: ${imageName}`) + (hasImage ? noumena.success(" (pulled)") : noumena.grayDim(" (not pulled)")));
    if (type === "MCP_STDIO") {
      console.log(noumena.textDim(`  Transport: `) + noumena.text("STDIN/STDOUT (ephemeral containers)"));
    } else if (type === "MCP_HTTP" && hasImage) {
      console.log(noumena.textDim(`  Container: `) + (containerRunning ? noumena.success("Running") : noumena.grayDim("Stopped")));
    }
  }
  console.log(noumena.textDim(`  Tools: ${entry.enabledTools.length} enabled`));
  console.log();

  const options: { value: string; label: string; hint?: string }[] = [
    { value: "back", label: noumena.textDim("← Back") },
  ];

  // Enable/Disable toggle
  if (entry.enabled) {
    options.push({ value: "disable", label: noumena.warning("Disable service"), hint: "Stop accepting requests" });
  } else {
    options.push({ value: "enable", label: noumena.success("Enable service"), hint: "Start accepting requests" });
  }

  // Suspend/Resume
  if (entry.suspended) {
    options.push({ value: "resume", label: noumena.success("Resume service"), hint: "Lift emergency suspension" });
  } else {
    options.push({ value: "suspend", label: noumena.warning("Suspend service"), hint: "Emergency kill switch" });
  }

  // Container controls
  if (isDocker && hasImage && type !== "MCP_STDIO") {
    if (containerRunning) {
      options.push({ value: "stop", label: "Stop container", hint: "Stop Docker container" });
    } else {
      options.push({ value: "start", label: "Start container", hint: "Start Docker container" });
    }
  }

  if (isDocker && !hasImage) {
    options.push({ value: "pull", label: "Pull image", hint: `docker pull ${imageName}` });
  }

  options.push({ value: "tools", label: "Manage tools", hint: `${entry.enabledTools.length} tools` });

  // Discover tools (need Docker image or command)
  if ((isDocker && hasImage) || (!isDocker && command && type === "MCP_STDIO")) {
    options.push({ value: "discover", label: noumena.purple("Discover tools"), hint: "Query service for available tools" });
  }

  options.push({ value: "info", label: "View details" });
  options.push({ value: "delete", label: noumena.purpleDim("Delete service") });

  const action = await p.select({ message: "Action:", options });
  if (p.isCancel(action) || action === "back") return;

  const s = p.spinner();

  if (action === "enable" || action === "disable") {
    const newEnabled = action === "enable";
    const confirmed = await p.confirm({
      message: `${newEnabled ? "Enable" : "Disable"} ${displayName}?`,
      initialValue: true,
    });
    if (p.isCancel(confirmed) || !confirmed) return;

    s.start(`${newEnabled ? "Enabling" : "Disabling"} in PolicyStore...`);
    try {
      if (newEnabled) {
        await enableService(entry.serviceName);
      } else {
        await disableService(entry.serviceName);
      }
      try { await reloadGatewayConfig(); } catch { /* ok */ }
      s.stop(noumena.success(`✓ ${displayName} ${newEnabled ? "enabled" : "disabled"}`));
    } catch (error) {
      s.stop(noumena.error("✗ Failed"));
      p.log.error(`${error}`);
    }
  } else if (action === "suspend") {
    const confirmed = await p.confirm({
      message: `Suspend ${displayName}? This blocks ALL requests immediately.`,
      initialValue: false,
    });
    if (p.isCancel(confirmed) || !confirmed) return;

    s.start("Suspending service...");
    try {
      await suspendService(entry.serviceName);
      s.stop(noumena.success(`✓ ${displayName} suspended`));
    } catch (error) {
      s.stop(noumena.error("✗ Failed"));
      p.log.error(`${error}`);
    }
  } else if (action === "resume") {
    s.start("Resuming service...");
    try {
      await resumeService(entry.serviceName);
      s.stop(noumena.success(`✓ ${displayName} resumed`));
    } catch (error) {
      s.stop(noumena.error("✗ Failed"));
      p.log.error(`${error}`);
    }
  } else if (action === "start" && imageName) {
    s.start("Starting container...");
    const result = startDockerContainer(imageName, `mcp-${entry.serviceName}`);
    if (result.success) {
      s.stop(noumena.success("Container started"));
    } else {
      s.stop(noumena.purpleDim("Failed to start container"));
      if (result.error) p.log.warn(result.error);
    }
  } else if (action === "stop" && imageName) {
    const cid = getContainerId(imageName);
    if (cid) {
      s.start("Stopping container...");
      stopDockerContainer(cid)
        ? s.stop(noumena.success("Container stopped"))
        : s.stop(noumena.purpleDim("Failed to stop container"));
    }
  } else if (action === "pull" && imageName) {
    console.log();
    console.log(noumena.purple(`  Pulling ${imageName}...`));
    console.log();
    try {
      execSync(`docker pull ${imageName}`, { stdio: "inherit" });
      console.log();
      p.log.success("Image pulled successfully");
    } catch {
      console.log();
      p.log.error("Failed to pull image");
    }
  } else if (action === "discover") {
    await discoverAndSaveTools(entry);
  } else if (action === "tools") {
    await manageToolsForService(entry);
  } else if (action === "info") {
    await viewServiceInfo(entry);
  } else if (action === "delete") {
    const confirmed = await p.confirm({
      message: `Delete ${noumena.purple(displayName)}? This cannot be undone.`,
      initialValue: false,
    });
    if (!p.isCancel(confirmed) && confirmed) {
      s.start("Removing from PolicyStore...");
      try {
        await removeServiceFromPolicyStore(entry.serviceName);
        try { await reloadGatewayConfig(); } catch { /* ok */ }
        s.stop(noumena.success(`${displayName} deleted`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
    }
  }
}

// ============================================================================
// Tool Management — reads/writes PolicyStore directly
// ============================================================================

async function manageToolsForService(entry: ServiceEntry): Promise<void> {
  while (true) {
    // Refresh from PolicyStore
    let freshData: PolicyData;
    try {
      freshData = await getPolicyData();
    } catch {
      p.log.error("Failed to read PolicyStore");
      return;
    }
    const freshEntry = freshData.services[entry.serviceName];
    if (!freshEntry) {
      p.log.warn("Service not found");
      return;
    }

    const enabledTools = freshEntry.enabledTools;
    const displayName = freshEntry.metadata?.displayName || freshEntry.serviceName;

    console.log();
    console.log(noumena.purple(`  ${displayName} - Tools`));
    console.log(noumena.textDim(`  ${enabledTools.length} enabled`));
    console.log();

    const action = await p.select({
      message: "What do you want to do?",
      options: [
        { value: "back" as const, label: noumena.textDim("← Back") },
        { value: "enable" as const, label: noumena.success("Enable tool"), hint: "Enter tool name to enable" },
        { value: "disable" as const, label: noumena.warning("Disable tools"), hint: "Pick tools with SPACE, then ENTER" },
        { value: "view" as const, label: "View enabled tools", hint: `${enabledTools.length} tools` },
      ],
    });

    if (p.isCancel(action) || action === "back") break;

    if (action === "view") {
      console.log();
      if (enabledTools.length === 0) {
        console.log(noumena.textDim("  No tools enabled"));
      } else {
        for (const tool of enabledTools) {
          console.log(`  ${noumena.success("✓")} ${entry.serviceName}.${tool}`);
        }
      }
      console.log();
      await p.confirm({ message: "Press Enter to continue", initialValue: true });
      continue;
    }

    if (action === "enable") {
      const toolName = await p.text({
        message: "Tool name to enable:",
        placeholder: "e.g., search, get_weather",
        validate: (v) => (!v || v.trim().length === 0 ? "Tool name is required" : undefined),
      });
      if (p.isCancel(toolName)) continue;

      const s = p.spinner();
      s.start("Enabling tool in PolicyStore...");
      try {
        await enableTool(entry.serviceName, String(toolName).trim());
        s.stop(noumena.success(`✓ Tool '${toolName}' enabled`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
      continue;
    }

    if (action === "disable") {
      if (enabledTools.length === 0) {
        p.log.info("No tools to disable");
        continue;
      }

      const selectedTools = await p.multiselect({
        message: "Select tools to disable:",
        options: enabledTools.map((tool) => ({
          value: tool,
          label: tool,
        })),
        required: false,
      });

      if (p.isCancel(selectedTools) || selectedTools.length === 0) continue;

      const s = p.spinner();
      s.start(`Disabling ${selectedTools.length} tool(s)...`);
      try {
        for (const toolName of selectedTools) {
          await disableTool(entry.serviceName, toolName);
        }
        s.stop(noumena.success(`✓ ${selectedTools.length} tool(s) disabled`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
    }
  }
}

// ============================================================================
// View Service Info
// ============================================================================

async function viewServiceInfo(entry: ServiceEntry): Promise<void> {
  const displayName = entry.metadata?.displayName || entry.serviceName;
  const type = entry.metadata?.type || "MCP_STDIO";

  console.log();
  console.log(noumena.purple(`  ${displayName}`));
  console.log();
  console.log(noumena.textDim("  Name: ") + entry.serviceName);
  console.log(noumena.textDim("  Type: ") + getTypeLabel(type));
  console.log(noumena.textDim("  Enabled: ") + (entry.enabled ? noumena.success("Yes") : noumena.grayDim("No")));
  console.log(noumena.textDim("  Suspended: ") + (entry.suspended ? noumena.warning("Yes") : noumena.grayDim("No")));
  if (entry.metadata?.description) {
    console.log(noumena.textDim("  Description: ") + entry.metadata.description);
  }
  if (entry.metadata?.command) {
    console.log(noumena.textDim("  Command: ") + entry.metadata.command);
  }
  if (entry.metadata?.endpoint) {
    console.log(noumena.textDim("  Endpoint: ") + entry.metadata.endpoint);
  }

  console.log();
  console.log(noumena.purple(`  Enabled Tools (${entry.enabledTools.length}):`));
  for (const tool of entry.enabledTools) {
    console.log(`    ${noumena.success("✓")} ${entry.serviceName}.${tool}`);
  }

  // Show metadata
  const metaKeys = Object.keys(entry.metadata || {}).filter(
    (k) => !["displayName", "type", "command", "args", "description", "endpoint", "baseUrl"].includes(k)
  );
  if (metaKeys.length > 0) {
    console.log();
    console.log(noumena.purple("  Extra Metadata:"));
    for (const key of metaKeys) {
      console.log(noumena.textDim(`    ${key}: `) + entry.metadata[key]);
    }
  }

  console.log();
  await p.confirm({ message: "Press Enter to continue", initialValue: true });
}

// ============================================================================
// Discover Tools
// ============================================================================

async function discoverAndSaveTools(entry: ServiceEntry): Promise<number> {
  const s = p.spinner();
  const imageName = getDockerImageFromMetadata(entry);
  const isDocker = imageName !== null;
  const command = entry.metadata?.command;
  const type = entry.metadata?.type || "MCP_STDIO";
  const argsStr = entry.metadata?.args;
  const args = argsStr ? JSON.parse(argsStr) : [];

  let result: { success: boolean; tools: any[]; serverInfo?: { name: string; version: string }; error?: string };

  if (isDocker && imageName) {
    s.start(`Discovering tools from ${imageName}...`);
    result = discoverToolsFromContainer(imageName);
  } else if (command && type === "MCP_STDIO") {
    const label = args.length ? `${command} ${args[0]}` : command;
    s.start(`Discovering tools from ${label}...`);
    result = discoverToolsFromCommand(command, args);
  } else {
    return 0;
  }

  if (!result.success || result.tools.length === 0) {
    s.stop(noumena.warning(
      result.error ? `Could not discover tools: ${result.error}` : "No tools found"
    ));
    return 0;
  }

  const serverLabel = result.serverInfo
    ? ` (${result.serverInfo.name} v${result.serverInfo.version})`
    : "";
  s.stop(noumena.success(`Discovered ${result.tools.length} tool(s)${serverLabel}`));

  // Show summary
  for (const tool of result.tools) {
    const desc = (tool.description || "").length > 60
      ? tool.description.substring(0, 57) + "..."
      : tool.description;
    console.log(noumena.textDim(`    ${noumena.grayDim("–")} ${tool.name}: ${desc}`));
  }
  console.log();

  // Enable all discovered tools in PolicyStore
  const enableSpinner = p.spinner();
  enableSpinner.start("Enabling discovered tools in PolicyStore...");
  try {
    for (const tool of result.tools) {
      await enableTool(entry.serviceName, tool.name);
    }
    enableSpinner.stop(noumena.success(`✓ ${result.tools.length} tools enabled`));
  } catch (error) {
    enableSpinner.stop(noumena.warning("Some tools may not have been enabled"));
    p.log.warn(`${error}`);
  }

  await p.confirm({ message: "Press Enter to continue", initialValue: true });
  return result.tools.length;
}

// ============================================================================
// Search Docker Hub
// ============================================================================

async function searchDockerHubFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Search Docker Hub"));
  console.log(noumena.textDim("  Find MCP servers from the official mcp/* namespace"));
  console.log();

  const query = await p.text({
    message: "Search term (leave empty for all):",
    placeholder: "e.g., fetch, github, slack, filesystem...",
  });
  if (p.isCancel(query)) return;

  const searchTerm = query?.trim() || undefined;
  const s = p.spinner();
  s.start(searchTerm ? `Searching for "${searchTerm}"...` : "Loading all MCP servers...");

  let servers;
  try {
    servers = await discoverMcpServers(searchTerm, 100);
  } catch (err) {
    s.stop(noumena.purpleDim("Failed to search Docker Hub"));
    p.log.warn(`Error: ${err}`);
    return;
  }
  s.stop();

  if (servers.length === 0) {
    p.log.warn(searchTerm ? `No servers found matching "${searchTerm}"` : "No MCP servers found");
    return;
  }

  p.log.info(`Found ${servers.length} MCP servers`);

  // Check what's already in PolicyStore
  let existingServices: Set<string>;
  try {
    const data = await getPolicyData();
    existingServices = new Set(Object.keys(data.services));
  } catch {
    existingServices = new Set();
  }

  const formatPulls = (count: number): string => {
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
    if (count >= 1000) return `${Math.round(count / 1000)}K`;
    return count.toString();
  };

  const selected = await p.select({
    message: `Select an MCP server to add (${servers.length} available):`,
    options: [
      { value: "back", label: noumena.textDim("← Back"), hint: "" },
      ...servers.map((server) => {
        const added = existingServices.has(server.name);
        return {
          value: server.name,
          label: `mcp/${server.name}` + (added ? noumena.textDim(" [added]") : ""),
          hint: `${formatPulls(server.pullCount)} pulls`,
        };
      }),
    ],
  });

  if (p.isCancel(selected) || selected === "back") return;

  if (existingServices.has(selected)) {
    p.log.warn(`${selected} is already configured`);
    return;
  }

  const server = servers.find((s) => s.name === selected);
  if (!server) return;

  // Pull image?
  const shouldPull = await p.confirm({ message: "Pull Docker image now?", initialValue: true });
  if (!p.isCancel(shouldPull) && shouldPull) {
    console.log();
    console.log(noumena.purple(`  Pulling mcp/${server.name}...`));
    console.log();
    try {
      execSync(`docker pull mcp/${server.name}`, { stdio: "inherit" });
      console.log();
      p.log.success("Image pulled successfully");
    } catch {
      console.log();
      p.log.warn("Failed to pull image - you can pull manually later");
    }
  }

  // Register in PolicyStore + set metadata
  const addSpinner = p.spinner();
  addSpinner.start("Registering service in PolicyStore...");
  try {
    const displayName = server.name.split("-").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
    await registerService(server.name);
    await setServiceMetadata(server.name, "displayName", displayName);
    await setServiceMetadata(server.name, "type", "MCP_STDIO");
    await setServiceMetadata(server.name, "command", `docker run -i --rm mcp/${server.name}`);
    await setServiceMetadata(server.name, "description", server.description);
    addSpinner.stop(noumena.success(`Added: mcp/${server.name}`));
  } catch (error) {
    addSpinner.stop(noumena.error("✗ Failed to register"));
    p.log.error(`${error}`);
    return;
  }

  // Auto-discover tools
  const imagePulled = !p.isCancel(shouldPull) && shouldPull;
  if (imagePulled || imageExists(`mcp/${server.name}`)) {
    const fakeEntry: ServiceEntry = {
      serviceName: server.name,
      enabled: false,
      enabledTools: [],
      suspended: false,
      metadata: { command: `docker run -i --rm mcp/${server.name}`, type: "MCP_STDIO" },
    };
    await discoverAndSaveTools(fakeEntry);
  }

  p.log.info("Service is disabled by default. Select it to enable.");
  try { await reloadGatewayConfig(); } catch { /* ok */ }
}

// ============================================================================
// Add Custom Service
// ============================================================================

const SERVICE_TEMPLATES: Record<string, {
  displayName: string;
  description: string;
  command: string;
  args: string[];
  setupGuide: string;
}> = {
  gemini: {
    displayName: "Google Gemini",
    description: "Google Gemini AI API for text generation and chat",
    command: "docker run -i --rm node:22-slim npx",
    args: ["-y", "@houtini/gemini-mcp"],
    setupGuide: "You'll need a Gemini API key from https://aistudio.google.com/apikey",
  },
  github: {
    displayName: "GitHub",
    description: "GitHub API for repository management and code operations",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-github"],
    setupGuide: "You'll need a GitHub Personal Access Token from https://github.com/settings/tokens",
  },
  filesystem: {
    displayName: "Filesystem",
    description: "Local filesystem access for reading and writing files",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-filesystem"],
    setupGuide: "Provides safe filesystem operations within configured directories",
  },
  memory: {
    displayName: "Memory",
    description: "Persistent memory/knowledge graph storage",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-memory"],
    setupGuide: "Stores and retrieves information across sessions",
  },
  brave_search: {
    displayName: "Brave Search",
    description: "Web search via Brave Search API",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-brave-search"],
    setupGuide: "You'll need a Brave Search API key from https://brave.com/search/api/",
  },
};

async function addCustomImageFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add MCP Service"));
  console.log(noumena.textDim("  Choose how you want to add a service"));
  console.log();

  const serviceType = await p.select({
    message: "Service type:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      { value: "template", label: "  Quick Start (Common Services)", hint: "Pre-configured templates" },
      { value: "npm", label: "  NPM Package", hint: "Run via npx (most MCP servers)" },
      { value: "docker", label: "  Docker Image", hint: "Local or registry image" },
    ],
  });
  if (p.isCancel(serviceType) || serviceType === "back") return;

  if (serviceType === "template") await addTemplateServiceFlow();
  else if (serviceType === "npm") await addNpmServiceFlow();
  else if (serviceType === "docker") await addDockerServiceFlow();
}

async function addTemplateServiceFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Quick Start: Common Services"));
  console.log();

  const templateName = await p.select({
    message: "Select service:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      ...Object.entries(SERVICE_TEMPLATES).map(([key, template]) => ({
        value: key,
        label: template.displayName,
        hint: template.description.substring(0, 50),
      })),
    ],
  });
  if (p.isCancel(templateName) || templateName === "back") return;

  const template = SERVICE_TEMPLATES[String(templateName)];
  console.log();
  console.log(noumena.success(`  ✓ Selected: ${template.displayName}`));
  console.log(noumena.textDim(`  ${template.setupGuide}`));
  console.log();

  const confirmed = await p.confirm({ message: `Add ${template.displayName}?`, initialValue: true });
  if (p.isCancel(confirmed) || !confirmed) return;

  const s = p.spinner();
  s.start("Registering in PolicyStore...");
  try {
    const name = String(templateName);
    await registerService(name);
    await setServiceMetadata(name, "displayName", template.displayName);
    await setServiceMetadata(name, "type", "MCP_STDIO");
    await setServiceMetadata(name, "command", template.command);
    await setServiceMetadata(name, "args", JSON.stringify(template.args));
    await setServiceMetadata(name, "description", template.description);
    await enableService(name);
    try { await reloadGatewayConfig(); } catch { /* ok */ }
    s.stop(noumena.success(`✓ Added: ${template.displayName}`));
  } catch (error) {
    s.stop(noumena.error("✗ Failed"));
    p.log.error(`${error}`);
  }
}

async function addNpmServiceFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add NPM-based MCP Service"));
  console.log();

  const packageName = await p.text({
    message: "NPM package name:",
    placeholder: "e.g., @modelcontextprotocol/server-github",
    validate: (v) => (!v || v.trim().length === 0 ? "Package name is required" : undefined),
  });
  if (p.isCancel(packageName)) return;

  const packageNameStr = String(packageName).trim();
  const extractName = (pkg: string): string => {
    const parts = pkg.split("/");
    return parts[parts.length - 1].replace("server-", "").replace(/@/g, "");
  };

  const serviceName = await p.text({
    message: "Service name:",
    initialValue: extractName(packageNameStr),
    validate: (v) => {
      if (!v || v.trim().length === 0) return "Service name is required";
      if (!/^[a-z0-9_-]+$/.test(v)) return "Use lowercase letters, numbers, hyphens, and underscores only";
      return undefined;
    },
  });
  if (p.isCancel(serviceName)) return;

  const serviceNameStr = String(serviceName).trim();
  const displayName = await p.text({
    message: "Display name:",
    initialValue: serviceNameStr.split(/[-_]/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" "),
  });
  if (p.isCancel(displayName)) return;

  const description = await p.text({
    message: "Description:",
    initialValue: `MCP service: ${displayName}`,
  });
  if (p.isCancel(description)) return;

  const s = p.spinner();
  s.start("Registering in PolicyStore...");
  try {
    await registerService(serviceNameStr);
    await setServiceMetadata(serviceNameStr, "displayName", String(displayName).trim());
    await setServiceMetadata(serviceNameStr, "type", "MCP_STDIO");
    await setServiceMetadata(serviceNameStr, "command", "npx");
    await setServiceMetadata(serviceNameStr, "args", JSON.stringify(["-y", packageNameStr]));
    await setServiceMetadata(serviceNameStr, "description", String(description).trim());
    await enableService(serviceNameStr);
    try { await reloadGatewayConfig(); } catch { /* ok */ }
    s.stop(noumena.success(`✓ Added: ${displayName}`));
  } catch (error) {
    s.stop(noumena.error("✗ Failed"));
    p.log.error(`${error}`);
  }

  p.log.info("Don't forget to grant user access via 'User Management'");
}

async function addDockerServiceFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add Docker-based MCP Service"));
  console.log();

  const imageName = await p.text({
    message: "Docker image name:",
    placeholder: "e.g., my-mcp-server or ghcr.io/myorg/mcp-server:latest",
    validate: (v) => {
      if (!v || v.trim().length === 0) return "Image name is required";
      if (/\s/.test(v)) return "Image name cannot contain spaces";
      return undefined;
    },
  });
  if (p.isCancel(imageName)) return;

  const imageNameStr = imageName.trim();
  const extractName = (img: string): string => {
    const withoutTag = img.split(":")[0];
    const parts = withoutTag.split("/");
    return parts[parts.length - 1];
  };

  const serviceName = await p.text({
    message: "Service name:",
    initialValue: extractName(imageNameStr),
    validate: (v) => {
      if (!v || v.trim().length === 0) return "Service name is required";
      if (!/^[a-z0-9_-]+$/.test(v)) return "Use lowercase letters, numbers, hyphens, and underscores only";
      return undefined;
    },
  });
  if (p.isCancel(serviceName)) return;

  const serviceNameStr = serviceName.trim();
  const displayName = await p.text({
    message: "Display name:",
    initialValue: serviceNameStr.split("-").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" "),
  });
  if (p.isCancel(displayName)) return;

  const description = await p.text({
    message: "Description:",
    initialValue: `Custom MCP service: ${displayName}`,
  });
  if (p.isCancel(description)) return;

  // Pull if not exists
  if (!imageExists(imageNameStr)) {
    const shouldPull = await p.confirm({ message: "Pull image now?", initialValue: true });
    if (!p.isCancel(shouldPull) && shouldPull) {
      console.log();
      try {
        execSync(`docker pull ${imageNameStr}`, { stdio: "inherit" });
        console.log();
        p.log.success("Image pulled");
      } catch {
        console.log();
        p.log.warn("Failed to pull image");
      }
    }
  }

  const s = p.spinner();
  s.start("Registering in PolicyStore...");
  try {
    await registerService(serviceNameStr);
    await setServiceMetadata(serviceNameStr, "displayName", String(displayName).trim());
    await setServiceMetadata(serviceNameStr, "type", "MCP_STDIO");
    await setServiceMetadata(serviceNameStr, "command", `docker run -i --rm ${imageNameStr}`);
    await setServiceMetadata(serviceNameStr, "description", String(description).trim());
    try { await reloadGatewayConfig(); } catch { /* ok */ }
    s.stop(noumena.success(`✓ Added: ${displayName}`));
  } catch (error) {
    s.stop(noumena.error("✗ Failed"));
    p.log.error(`${error}`);
  }

  // Auto-discover if image exists
  if (imageExists(imageNameStr)) {
    const fakeEntry: ServiceEntry = {
      serviceName: serviceNameStr,
      enabled: false,
      enabledTools: [],
      suspended: false,
      metadata: { command: `docker run -i --rm ${imageNameStr}`, type: "MCP_STDIO" },
    };
    await discoverAndSaveTools(fakeEntry);
  }
}

// ============================================================================
// User Management — grants-only model
// ============================================================================

const SYSTEM_USERNAMES = new Set(["admin", "service-account-mcpgateway"]);

function filterSystemUsers(users: KeycloakUser[]): KeycloakUser[] {
  return users.filter((u) => !SYSTEM_USERNAMES.has(u.username));
}

async function userManagementFlow(): Promise<void> {
  while (true) {
    let keycloakUsers: KeycloakUser[] = [];
    let keycloakError = "";
    try {
      keycloakUsers = await listKeycloakUsers();
    } catch (err) {
      keycloakError = `${err}`;
    }

    const isSystemAccount = (user: KeycloakUser): boolean => {
      const username = user.username?.toLowerCase() || "";
      const email = user.email?.toLowerCase() || "";
      if (username === "admin" || username === "agent") return true;
      if (email === "admin@acme.com" || email === "agent@acme.com") return true;
      const roles = user.attributes?.role || [];
      if (roles.includes("admin") || roles.includes("gateway")) return true;
      return false;
    };

    const regularUsers = keycloakUsers.filter((u) => !isSystemAccount(u));
    const systemUsers = keycloakUsers.filter((u) => isSystemAccount(u));

    // Get grants from PolicyStore
    let policyData: PolicyData;
    try {
      policyData = await getPolicyData();
    } catch {
      policyData = { services: {}, grants: {}, revokedSubjects: [], contextualRoutes: {} };
    }

    console.clear();
    console.log();
    console.log(noumena.purple("  User Management"));
    console.log();
    console.log(noumena.textDim("  Grants-only model: grant/revoke tools per user"));
    console.log(noumena.textDim("  Users come from Keycloak. Grants live in PolicyStore."));
    console.log();
    console.log(noumena.textDim(`  ${regularUsers.length} user(s) in Keycloak (${systemUsers.length} system accounts hidden)`));
    if (keycloakError) {
      console.log(noumena.warning(`  Keycloak error: ${keycloakError.substring(0, 60)}`));
    }
    console.log();

    const userOptions: { value: string; label: string; hint?: string }[] = regularUsers.map((kcUser) => {
      const email = kcUser.email || "(no email)";
      const role = kcUser.attributes?.role?.[0] || "user";
      const userGrants = policyData.grants[email] || [];
      const isRevoked = policyData.revokedSubjects.includes(email);
      const grantCount = userGrants.reduce((sum, g) => sum + g.allowedTools.length, 0);

      let statusIcon: string;
      let grantInfo: string;
      if (isRevoked) {
        statusIcon = noumena.error("✗");
        grantInfo = "REVOKED";
      } else if (grantCount > 0) {
        statusIcon = noumena.success("✓");
        grantInfo = `${grantCount} tools`;
      } else {
        statusIcon = noumena.grayDim("○");
        grantInfo = "no grants";
      }

      return {
        value: `user:${kcUser.id}`,
        label: `${statusIcon}  ${kcUser.firstName || ""} ${kcUser.lastName || email} ${noumena.textDim(`(${role})`)}`,
        hint: `${email} | ${grantInfo}`,
      };
    });

    const allOptions: { value: string; label: string; hint?: string }[] = [
      { value: "back", label: noumena.textDim("← Back") },
      ...userOptions,
      { value: "create", label: noumena.accent("+ Create new Keycloak user"), hint: "Add user to identity system" },
    ];

    const action = await p.select({ message: "Select a user or action:", options: allOptions });
    if (p.isCancel(action) || action === "back") break;

    if (typeof action === "string" && action.startsWith("user:")) {
      const keycloakId = action.replace("user:", "");
      const kcUser = keycloakUsers.find((u) => u.id === keycloakId);
      if (!kcUser || !kcUser.email) continue;
      await userActionsFlow(kcUser, policyData);
    } else if (action === "create") {
      await createUserFlow();
    }
  }
}

async function userActionsFlow(kcUser: KeycloakUser, policyData: PolicyData): Promise<void> {
  const email = kcUser.email || "(no email)";
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || email;
  const userGrants = policyData.grants[email] || [];
  const isRevoked = policyData.revokedSubjects.includes(email);
  const grantCount = userGrants.reduce((sum, g) => sum + g.allowedTools.length, 0);

  console.log();
  console.log(noumena.purple(`  ${displayName}`));
  console.log(noumena.textDim(`  Email: ${email}`));
  console.log(noumena.textDim(`  Keycloak ID: ${kcUser.id}`));

  if (isRevoked) {
    console.log(noumena.error("  REVOKED — all access blocked"));
  } else if (grantCount > 0) {
    console.log(noumena.success(`  ✓ ${userGrants.length} service(s), ${grantCount} tool(s) granted`));
  } else {
    console.log(noumena.grayDim("  ○ No grants"));
  }
  console.log();

  const actionOptions: Array<{ value: string; label: string; hint?: string }> = [
    { value: "back", label: noumena.textDim("← Back") },
    { value: "grant", label: "Edit tool grants", hint: "Grant/revoke tools" },
    { value: "view", label: "View all grants", hint: "Show detailed permissions" },
  ];

  if (isRevoked) {
    actionOptions.push({ value: "reinstate", label: noumena.success("Reinstate subject"), hint: "Lift emergency revocation" });
  } else {
    actionOptions.push({ value: "revoke_subject", label: noumena.error("Emergency revoke"), hint: "Block ALL access immediately" });
  }

  actionOptions.push(
    { value: "delete", label: noumena.purpleDim("Delete from Keycloak"), hint: "Remove identity" },
  );

  const action = await p.select({ message: "Action:", options: actionOptions });
  if (p.isCancel(action) || action === "back") return;

  if (action === "grant") {
    await editUserGrantsFlow(email, policyData);
  } else if (action === "view") {
    await viewUserGrantsFlow(email, policyData);
  } else if (action === "revoke_subject") {
    const confirmed = await p.confirm({
      message: `Emergency revoke ALL access for ${noumena.purple(displayName)}?`,
      initialValue: false,
    });
    if (!p.isCancel(confirmed) && confirmed) {
      const s = p.spinner();
      s.start("Revoking subject...");
      try {
        await revokeSubject(email);
        s.stop(noumena.success(`✓ ${displayName} revoked`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
    }
  } else if (action === "reinstate") {
    const s = p.spinner();
    s.start("Reinstating subject...");
    try {
      await reinstateSubject(email);
      s.stop(noumena.success(`✓ ${displayName} reinstated`));
    } catch (error) {
      s.stop(noumena.error("✗ Failed"));
      p.log.error(`${error}`);
    }
  } else if (action === "delete") {
    await deleteUserFromKeycloakFlow(kcUser);
  }
}

async function editUserGrantsFlow(email: string, policyData: PolicyData): Promise<void> {
  const services = Object.values(policyData.services).filter((s) => s.enabled);
  if (services.length === 0) {
    p.log.warn("No services are enabled");
    return;
  }

  while (true) {
    // Refresh grants
    let freshData: PolicyData;
    try {
      freshData = await getPolicyData();
    } catch {
      p.log.error("Failed to read PolicyStore");
      return;
    }
    const userGrants = freshData.grants[email] || [];
    const grantsByService = new Map(userGrants.map((g) => [g.serviceName, g]));

    console.log();
    console.log(noumena.purple(`  Tool Grants: ${email}`));
    console.log();

    const serviceOptions = Object.values(freshData.services)
      .filter((s) => s.enabled)
      .map((service) => {
        const grant = grantsByService.get(service.serviceName);
        const hasAccess = !!grant;
        const isWildcard = grant?.allowedTools.includes("*");
        const toolCount = grant ? grant.allowedTools.length : 0;

        const icon = hasAccess ? noumena.success("✓") : noumena.grayDim("–");
        const hint = isWildcard ? "All tools" : hasAccess ? `${toolCount} tools` : "No access";
        const displayName = service.metadata?.displayName || service.serviceName;

        return {
          value: service.serviceName,
          label: `${icon}  ${displayName}`,
          hint,
        };
      });

    const selected = await p.select({
      message: "Select a service to manage grants:",
      options: [
        { value: "back", label: noumena.textDim("← Back") },
        ...serviceOptions,
      ],
    });

    if (p.isCancel(selected) || selected === "back") break;

    const service = freshData.services[selected];
    if (service) {
      await manageUserServiceGrantsFlow(email, service, freshData);
    }
  }
}

async function manageUserServiceGrantsFlow(
  email: string,
  service: ServiceEntry,
  policyData: PolicyData
): Promise<void> {
  while (true) {
    // Refresh
    let freshData: PolicyData;
    try {
      freshData = await getPolicyData();
    } catch {
      p.log.error("Failed to read PolicyStore");
      return;
    }

    const userGrants = freshData.grants[email] || [];
    const grant = userGrants.find((g) => g.serviceName === service.serviceName);
    const hasWildcard = grant?.allowedTools.includes("*") || false;
    const grantedTools = grant ? grant.allowedTools : [];
    const displayName = service.metadata?.displayName || service.serviceName;

    console.log();
    console.log(noumena.purple(`  ${displayName} — ${email}`));
    console.log(noumena.textDim(`  ${hasWildcard ? "ALL tools" : `${grantedTools.length} tools`} granted`));
    console.log();

    const action = await p.select({
      message: "What do you want to do?",
      options: [
        { value: "back" as const, label: noumena.textDim("← Back") },
        { value: "grant" as const, label: noumena.success("Grant tool"), hint: "Enter tool name" },
        { value: "grant_all" as const, label: "Grant all tools", hint: "Wildcard access" },
        { value: "revoke" as const, label: noumena.warning("Revoke tools"), hint: "Pick tools to revoke" },
        { value: "revoke_all" as const, label: "Revoke all access", hint: "Remove all grants for this service" },
      ],
    });

    if (p.isCancel(action) || action === "back") break;

    const s = p.spinner();

    if (action === "grant_all") {
      s.start(`Granting all ${displayName} tools...`);
      try {
        await grantAllToolsForService(email, service.serviceName);
        s.stop(noumena.success("✓ All tools granted"));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
      continue;
    }

    if (action === "revoke_all") {
      const confirmed = await p.confirm({
        message: `Revoke ALL access to ${displayName}?`,
        initialValue: false,
      });
      if (!p.isCancel(confirmed) && confirmed) {
        s.start("Revoking...");
        try {
          await revokeServiceAccess(email, service.serviceName);
          s.stop(noumena.success("✓ Service access revoked"));
        } catch (error) {
          s.stop(noumena.error("✗ Failed"));
          p.log.error(`${error}`);
        }
      }
      continue;
    }

    if (action === "grant") {
      const toolName = await p.text({
        message: "Tool name to grant:",
        placeholder: "e.g., search, list_repos",
        validate: (v) => (!v || v.trim().length === 0 ? "Tool name is required" : undefined),
      });
      if (p.isCancel(toolName)) continue;

      s.start("Granting tool...");
      try {
        await grantTool(email, service.serviceName, String(toolName).trim());
        s.stop(noumena.success(`✓ Granted ${toolName}`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
      continue;
    }

    if (action === "revoke") {
      if (grantedTools.length === 0) {
        p.log.info("No tools to revoke");
        continue;
      }

      const selectedTools = await p.multiselect({
        message: "Select tools to revoke:",
        options: grantedTools.map((tool) => ({ value: tool, label: tool })),
        required: false,
      });
      if (p.isCancel(selectedTools) || selectedTools.length === 0) continue;

      s.start(`Revoking ${selectedTools.length} tool(s)...`);
      try {
        for (const toolName of selectedTools) {
          await revokeTool(email, service.serviceName, toolName);
        }
        s.stop(noumena.success(`✓ ${selectedTools.length} tool(s) revoked`));
      } catch (error) {
        s.stop(noumena.error("✗ Failed"));
        p.log.error(`${error}`);
      }
    }
  }
}

async function viewUserGrantsFlow(email: string, policyData: PolicyData): Promise<void> {
  const userGrants = policyData.grants[email] || [];

  console.log();
  console.log(noumena.purple(`  Grants: ${email}`));
  console.log();

  if (userGrants.length === 0) {
    console.log(noumena.textDim("  No grants"));
  } else {
    for (const grant of userGrants) {
      const service = policyData.services[grant.serviceName];
      const displayName = service?.metadata?.displayName || grant.serviceName;

      if (grant.allowedTools.includes("*")) {
        console.log(noumena.success(`  ✓ ${displayName}`));
        console.log(noumena.textDim("    ALL TOOLS"));
      } else {
        console.log(noumena.success(`  ✓ ${displayName}`));
        grant.allowedTools.forEach((tool) => {
          console.log(noumena.textDim(`    - ${tool}`));
        });
      }
      console.log();
    }
  }

  if (policyData.revokedSubjects.includes(email)) {
    console.log(noumena.error("  ⚠ SUBJECT IS REVOKED — all access blocked regardless of grants"));
    console.log();
  }

  await p.confirm({ message: "Press Enter to continue", initialValue: true });
}

async function createUserFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Create New User"));
  console.log();

  const email = await p.text({
    message: "Email:",
    initialValue: "newuser@acme.com",
    validate: (v) => {
      if (!v || !v.includes("@") || !v.includes(".")) return "Valid email required";
      return undefined;
    },
  });
  if (p.isCancel(email)) return;

  const emailStr = String(email).trim();
  const username = await p.text({ message: "Username:", initialValue: emailStr.split("@")[0] });
  if (p.isCancel(username)) return;

  const firstName = await p.text({ message: "First name:", placeholder: "Optional" });
  if (p.isCancel(firstName)) return;

  const lastName = await p.text({ message: "Last name:", placeholder: "Optional" });
  if (p.isCancel(lastName)) return;

  const password = await p.password({
    message: "Initial password:",
    validate: (v) => (!v || v.length < 8 ? "Password must be at least 8 characters" : undefined),
  });
  if (p.isCancel(password)) return;

  const s = p.spinner();
  s.start("Creating user in Keycloak...");
  try {
    await createKeycloakUser(
      emailStr,
      String(username).trim(),
      firstName ? String(firstName).trim() : undefined,
      lastName ? String(lastName).trim() : undefined,
      String(password)
    );
    s.stop(noumena.success(`User created: ${emailStr}`));
    p.log.info("Use 'Edit tool grants' to grant access to this user");
  } catch (error) {
    s.stop(noumena.error("✗ Failed"));
    p.log.error(`${error}`);
  }
}

async function deleteUserFromKeycloakFlow(kcUser: KeycloakUser): Promise<void> {
  const email = kcUser.email || "(no email)";
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || email;

  console.log();
  console.log(noumena.warning("  WARNING: This will delete the user's identity!"));
  console.log();

  const confirmed = await p.confirm({
    message: `Delete ${noumena.purple(displayName)} from Keycloak?`,
    initialValue: false,
  });
  if (p.isCancel(confirmed) || !confirmed) return;

  const s = p.spinner();

  // Revoke all grants in PolicyStore
  s.start("Revoking grants...");
  try {
    await revokeSubject(email);
    s.stop(noumena.success("Grants revoked"));
  } catch {
    s.stop(noumena.textDim("Grant revocation skipped"));
  }

  if (kcUser.id) {
    s.start("Deleting from Keycloak...");
    try {
      await deleteKeycloakUser(kcUser.id);
      s.stop(noumena.success("Deleted from Keycloak"));
    } catch (error) {
      s.stop(noumena.warning("Failed to delete from Keycloak"));
      p.log.warn(`${error}`);
    }
  }

  p.log.success(`User deleted: ${email}`);
}

// ============================================================================
// Configuration Manager — Import / Export
// ============================================================================

async function configurationManagerFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const configPath = getConfigPath();
  const configDir = path.dirname(configPath);

  while (true) {
    console.log();
    console.log(noumena.purple("  Import / Export"));
    console.log(noumena.textDim("  YAML is an import/export format. PolicyStore is the runtime source of truth."));
    console.log();

    let backupFiles: string[] = [];
    try {
      const files = await fs.readdir(configDir);
      backupFiles = files
        .filter((f) => f.startsWith("services.yaml.") && f.endsWith(".backup"))
        .sort()
        .reverse();
    } catch { /* ignore */ }

    const action = await p.select({
      message: "Select action:",
      options: [
        { value: "back", label: noumena.textDim("← Back") },
        { value: "import", label: "  Import YAML → PolicyStore", hint: "Load services.yaml into PolicyStore" },
        { value: "import-file", label: "  Import from file → PolicyStore", hint: "Load external YAML file" },
        { value: "export", label: "  Export PolicyStore → YAML", hint: "Snapshot PolicyStore to services.yaml" },
        { value: "---sep", label: noumena.grayDim("  ──────────"), hint: "" },
        { value: "backup", label: "  Create backup", hint: "Timestamped copy of services.yaml" },
        { value: "view", label: "  View services.yaml", hint: "Display current file" },
      ],
    });

    if (p.isCancel(action) || action === "back") return;
    if (typeof action === "string" && action.startsWith("---")) continue;

    if (action === "import") {
      await importFromYamlFlow();
    } else if (action === "import-file") {
      await importFromFileFlow();
    } else if (action === "export") {
      await exportToYamlFlow();
    } else if (action === "backup") {
      await backupFlow();
    } else if (action === "view") {
      await viewConfigFlow();
    }
  }
}

async function importFromYamlFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Import YAML → PolicyStore"));
  console.log(noumena.textDim("  Reads services.yaml and reconciles with PolicyStore"));
  console.log();

  const confirm = await p.confirm({
    message: "Import services.yaml into PolicyStore?",
    initialValue: false,
  });
  if (p.isCancel(confirm) || !confirm) return;

  const s = p.spinner();
  s.start("Importing...");
  try {
    const result = await importFromYaml();
    s.stop(noumena.success("Import completed"));
    p.log.info(`Services: ${result.servicesImported.join(", ") || "none"}`);
    p.log.info(`Grants: ${result.grantsImported.join(", ") || "none"}`);

    // Reload Gateway
    try {
      await reloadGatewayConfig();
      p.log.success("Gateway reloaded");
    } catch {
      p.log.warn("Gateway reload failed (may not be running)");
    }
  } catch (error) {
    s.stop(noumena.error("Import failed"));
    p.log.error(`${error}`);
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

async function importFromFileFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Import from File"));
  console.log();

  const filePath = await p.text({
    message: "Enter path to YAML file:",
    placeholder: "/path/to/services.yaml",
    validate: (v) => (!v || v.trim().length === 0 ? "Path is required" : undefined),
  });
  if (p.isCancel(filePath)) return;

  const fs = await import("fs/promises");
  const resolvedPath = path.resolve(String(filePath).trim());

  try {
    await fs.access(resolvedPath);
  } catch {
    p.log.error(`File not found: ${resolvedPath}`);
    return;
  }

  const confirm = await p.confirm({
    message: `Import ${resolvedPath} into PolicyStore?`,
    initialValue: false,
  });
  if (p.isCancel(confirm) || !confirm) return;

  // Copy file to services.yaml then import
  const s = p.spinner();
  s.start("Copying file...");
  try {
    const content = await fs.readFile(resolvedPath, "utf-8");
    await fs.writeFile(getConfigPath(), content, "utf-8");
    s.stop(noumena.success("File copied"));
  } catch (err: any) {
    s.stop(noumena.error("Copy failed"));
    p.log.error(`${err}`);
    return;
  }

  // Import to PolicyStore
  s.start("Importing to PolicyStore...");
  try {
    const result = await importFromYaml();
    s.stop(noumena.success("Import completed"));
    p.log.info(`Services: ${result.servicesImported.join(", ") || "none"}`);
    try { await reloadGatewayConfig(); } catch { /* ok */ }
  } catch (error) {
    s.stop(noumena.error("Import failed"));
    p.log.error(`${error}`);
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

async function exportToYamlFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Export PolicyStore → YAML"));
  console.log(noumena.textDim("  Snapshots PolicyStore state to services.yaml"));
  console.log();

  const confirm = await p.confirm({
    message: "Export PolicyStore to services.yaml?",
    initialValue: true,
  });
  if (p.isCancel(confirm) || !confirm) return;

  const s = p.spinner();
  s.start("Exporting...");
  try {
    await exportToYaml();
    s.stop(noumena.success("Exported to services.yaml"));
  } catch (error) {
    s.stop(noumena.error("Export failed"));
    p.log.error(`${error}`);
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

async function backupFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const configPath = getConfigPath();
  const configDir = path.dirname(configPath);

  const s = p.spinner();
  s.start("Creating backup...");
  try {
    const now = new Date();
    const timestamp = now.toISOString().replace(/:/g, "-").replace(/\..+/, "").replace("T", "_");
    const backupPath = path.join(configDir, `services.yaml.${timestamp}.backup`);
    const content = await fs.readFile(configPath, "utf-8");
    await fs.writeFile(backupPath, content, "utf-8");
    s.stop(noumena.success("Backup created"));
    p.log.info(`Saved to: ${path.basename(backupPath)}`);
  } catch (err: any) {
    s.stop(noumena.error("Backup failed"));
    p.log.error(`${err}`);
  }
}

async function viewConfigFlow(): Promise<void> {
  const configPath = getConfigPath();
  try {
    const contents = readFileSync(configPath, "utf-8");
    console.clear();
    console.log();
    console.log(noumena.purple("  services.yaml"));
    console.log(noumena.textDim(`  ${configPath}`));
    console.log();
    console.log(contents);
    console.log();
    await p.text({ message: "Press Enter to return...", defaultValue: "", placeholder: "" });
  } catch (error) {
    p.log.error(`Failed to read config: ${error}`);
  }
}

// ============================================================================
// Main entry point
// ============================================================================

async function main() {
  console.clear();
  p.intro(noumena.purple("◆ NOUMENA MCP Gateway Wizard"));

  // Step 1: Admin login
  const loggedIn = await adminLogin();
  if (!loggedIn) {
    p.outro(noumena.purpleDim("Login required. Goodbye."));
    return;
  }

  // Step 2: Check Gateway
  const s = p.spinner();
  s.start("Connecting to Gateway...");
  const connected = await checkGatewayHealth();
  if (connected) {
    s.stop(noumena.success("Gateway connected"));
  } else {
    s.stop(noumena.warning("Gateway not available (running in offline mode)"));
  }

  // Step 3: Ensure PolicyStore exists
  const bs = p.spinner();
  bs.start("Checking PolicyStore...");
  try {
    await ensurePolicyStore();
    const data = await getPolicyData();
    const serviceCount = Object.keys(data.services).length;
    bs.stop(noumena.success(`PolicyStore ready (${serviceCount} services)`));

    // If empty, offer to import from YAML
    if (serviceCount === 0) {
      const shouldImport = await p.confirm({
        message: "PolicyStore is empty. Import from services.yaml?",
        initialValue: true,
      });
      if (!p.isCancel(shouldImport) && shouldImport) {
        const is = p.spinner();
        is.start("Importing from YAML...");
        try {
          const result = await importFromYaml();
          is.stop(noumena.success(`Imported ${result.servicesImported.length} service(s)`));
        } catch (err) {
          is.stop(noumena.warning("Import failed"));
          p.log.warn(`${err}`);
        }
      }
    }
  } catch (err) {
    bs.stop(noumena.warning("PolicyStore not available"));
    p.log.warn(`${err}`);
  }

  // Main loop
  let running = true;
  while (running) {
    try {
      running = await mainMenu();
    } catch (error) {
      if (error instanceof Error && error.message.includes("cancelled")) {
        running = false;
      } else {
        p.log.error(`Error: ${error}`);
      }
    }
  }

  p.outro(noumena.purple("Goodbye! ◆"));
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
