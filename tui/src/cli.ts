#!/usr/bin/env node
/**
 * NOUMENA MCP Gateway Wizard
 * 
 * Interactive CLI for managing MCP Gateway services.
 * Uses @clack/prompts for a beautiful terminal experience.
 */

import * as p from "@clack/prompts";
import chalk from "chalk";
import { execSync } from "child_process";
import { 
  loadConfig, 
  setServiceEnabled, 
  removeService,
  addService,
  setToolEnabled,
  updateServiceTools,
  type ServiceDefinition,
} from "./lib/config.js";
import { 
  checkGatewayHealth, 
  reloadGatewayConfig, 
  syncServiceWithNpl,
  discoverMcpServers,
  getContainerStatus,
  startContainer,
  stopContainer,
  imageExists,
  discoverToolsFromContainer,
  discoveredToToolDefinitions,
  setAdminCredentials,
  validateCredentials,
  bootstrapNpl,
  isNplBootstrapped,
} from "./lib/api.js";

// Noumena color palette
const noumena = {
  purple: chalk.hex("#7C3AED"),      // Primary purple
  purpleDim: chalk.hex("#6B21A8"),   // Darker purple
  gray: chalk.hex("#6B7280"),
  grayDim: chalk.hex("#4B5563"),
  success: chalk.hex("#10B981"),     // Green for success
  warning: chalk.hex("#F59E0B"),     // Amber for warnings
  text: chalk.hex("#E5E7EB"),
  textDim: chalk.hex("#9CA3AF"),
};

/**
 * Display the header
 */
function showHeader() {
  console.log();
  console.log(noumena.purple("  ◆ NOUMENA MCP Gateway Wizard"));
  console.log();
}

/**
 * Admin login flow - prompts for credentials and validates against Keycloak.
 * Credentials are stored in memory only for the duration of the session.
 * Returns true if login was successful.
 */
async function adminLogin(): Promise<boolean> {
  console.log();
  console.log(noumena.purple("  Admin Login"));
  console.log(noumena.textDim("  Credentials are stored in memory only for this session."));
  console.log();

  // Allow env vars for CI/automation
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
      if (attempt < 2) {
        p.log.warn("Please try again.");
      }
    }
  }

  p.log.error("Too many failed attempts.");
  return false;
}

/**
 * NPL Bootstrap flow - creates ServiceRegistry and ToolExecutionPolicy if needed.
 */
async function nplBootstrapFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  NPL Bootstrap"));
  console.log(noumena.textDim("  Ensures ServiceRegistry and ToolExecutionPolicy exist in NPL."));
  console.log(noumena.textDim("  Also syncs enabled services from services.yaml."));
  console.log();

  const s = p.spinner();
  s.start("Bootstrapping NPL...");

  try {
    const result = await bootstrapNpl();
    s.stop(noumena.success("NPL bootstrapped"));

    if (result.registryCreated) {
      p.log.success("Created ServiceRegistry");
    } else {
      p.log.info("ServiceRegistry already exists");
    }

    if (result.policyCreated) {
      p.log.success("Created ToolExecutionPolicy");
    } else {
      p.log.info("ToolExecutionPolicy already exists");
    }

    if (result.servicesEnabled.length > 0) {
      p.log.info(`Enabled services: ${result.servicesEnabled.join(", ")}`);
    } else {
      p.log.info("No services enabled in services.yaml");
    }
  } catch (error) {
    s.stop(noumena.purpleDim("Bootstrap failed"));
    p.log.error(`${error}`);
    p.log.info("Make sure the NPL Engine is running and Keycloak is provisioned.");
  }
}

/**
 * Check if service is Docker-based and get image name
 */
function getDockerImageName(service: ServiceDefinition): string | null {
  if (service.type !== "MCP_STDIO" || !service.command) return null;
  
  // Check for docker run command
  if (service.command.includes("docker run")) {
    // Extract image name from docker run command (last argument before any flags)
    const match = service.command.match(/docker\s+run\s+[^]*?\s+([\w\-\/.]+)\s*$/);
    if (match) return match[1];
    
    // Try matching mcp/something pattern
    const mcpMatch = service.command.match(/mcp\/[\w-]+/);
    if (mcpMatch) return mcpMatch[0];
  }
  
  return null;
}

/**
 * Get type label for display
 */
function getTypeLabel(type: string): string {
  switch (type) {
    case "MCP_STDIO": return "STDIO";
    case "MCP_HTTP": return "HTTP";
    case "DIRECT_REST": return "REST";
    default: return type;
  }
}

/**
 * Show main menu and handle selection
 */
async function mainMenu(): Promise<boolean> {
  const config = loadConfig();
  const services = config.services;
  const gatewayConnected = await checkGatewayHealth();
  
  // Build status line
  const gatewayStatus = gatewayConnected 
    ? noumena.success("● Connected") 
    : noumena.grayDim("○ Disconnected");
  const enabledCount = services.filter(s => s.enabled).length;
  
  console.clear();
  showHeader();
  
  // Compact status bar with legend
  console.log(
    noumena.textDim("  Gateway: ") + gatewayStatus + 
    noumena.textDim("  |  Services: ") + noumena.text(`${enabledCount}/${services.length}`) +
    noumena.textDim("  |  ") +
    noumena.success("✓") + noumena.textDim(" on  ") +
    noumena.grayDim("–") + noumena.textDim(" off  ") +
    noumena.success("▶") + noumena.textDim(" running  ") +
    noumena.warning("■") + noumena.textDim(" stopped")
  );
  console.log();
  
  if (services.length === 0) {
    p.note("Use 'Search Docker Hub' or 'Add custom image' to get started.", "No services");
  }

  // Build service options with status indicators
  const serviceOptions = services.map(service => {
    const statusIcon = service.enabled ? noumena.success("✓") : noumena.grayDim("–");
    const imageName = getDockerImageName(service);
    let containerIcon = "";
    if (imageName) {
      const status = getContainerStatus(imageName);
      if (status.running) {
        containerIcon = noumena.success(" ▶");
      } else if (imageExists(imageName)) {
        containerIcon = noumena.warning(" ■");
      } else {
        containerIcon = noumena.grayDim(" ·");
      }
    }
    const enabledTools = service.tools.filter(t => t.enabled !== false).length;
    
    return {
      value: `service:${service.name}` as const,
      label: `${statusIcon}${containerIcon}  ${service.displayName}`,
      hint: `${getTypeLabel(service.type)} | ${enabledTools}/${service.tools.length} tools`,
    };
  });

  const action = await p.select({
    message: "Select a service or action:",
    options: [
      ...serviceOptions,
      { value: "search" as const, label: noumena.purple("+ Search Docker Hub"), hint: "Find and add MCP servers" },
      { value: "custom" as const, label: noumena.purple("+ Add custom image"), hint: "Local or private registry" },
      { value: "bootstrap" as const, label: noumena.purple("NPL Bootstrap"), hint: "Create/sync NPL protocol instances" },
      { value: "reload" as const, label: "Reload config", hint: "Reload services.yaml" },
      { value: "gateway" as const, label: "Reload Gateway", hint: "Tell Gateway to reload" },
      { value: "quit" as const, label: "Quit", hint: "" },
    ],
  });

  if (p.isCancel(action) || action === "quit") {
    return false;
  }

  if (typeof action === "string" && action.startsWith("service:")) {
    const serviceName = action.replace("service:", "");
    const service = services.find(s => s.name === serviceName);
    if (service) {
      await serviceActionsFlow(service);
    }
  } else if (action === "search") {
    await searchDockerHubFlow();
  } else if (action === "custom") {
    await addCustomImageFlow();
  } else if (action === "bootstrap") {
    await nplBootstrapFlow();
  } else if (action === "reload") {
    loadConfig(); // Force reload
    p.log.success("Configuration reloaded from disk");
  } else if (action === "gateway") {
    await reloadGatewayFlow();
  }

  return true;
}

/**
 * Service actions flow - shows actions for a selected service
 */
async function serviceActionsFlow(service: ServiceDefinition): Promise<void> {
  const imageName = getDockerImageName(service);
  const isDocker = imageName !== null;
  const containerStatus = isDocker ? getContainerStatus(imageName) : null;
  const hasImage = isDocker ? imageExists(imageName) : false;
  
  // Show service info header
  console.log();
  console.log(noumena.purple(`  ${service.displayName}`));
  console.log(noumena.textDim(`  Type: ${getTypeLabel(service.type)}`));
  console.log(noumena.textDim(`  Status: `) + (service.enabled ? noumena.success("Enabled") : noumena.grayDim("Disabled")));
  if (isDocker) {
    console.log(noumena.textDim(`  Image: ${imageName}`));
    console.log(noumena.textDim(`  Container: `) + (containerStatus?.running ? noumena.success("Running") : hasImage ? noumena.warning("Stopped") : noumena.grayDim("Not pulled")));
  }
  console.log();

  // Build action options based on service state
  const options: { value: string; label: string; hint?: string }[] = [
    { value: "back", label: noumena.textDim("← Back") },
  ];
  
  // Toggle enable/disable
  if (service.enabled) {
    options.push({ value: "disable", label: noumena.warning("Disable service"), hint: "Stop accepting requests" });
  } else {
    options.push({ value: "enable", label: noumena.success("Enable service"), hint: "Start accepting requests" });
  }
  
  // Container actions (only for Docker-based services)
  if (isDocker) {
    if (!hasImage) {
      options.push({ value: "pull", label: "Pull image", hint: `docker pull ${imageName}` });
    } else if (containerStatus?.running) {
      options.push({ value: "stop", label: "Stop container" });
      options.push({ value: "logs", label: "View logs" });
    } else {
      options.push({ value: "start", label: noumena.success("Start container") });
    }
  }
  
  // Other actions
  options.push({ value: "tools", label: "Manage tools", hint: `${service.tools.length} tools` });
  if (isDocker && hasImage) {
    options.push({ value: "discover", label: noumena.purple("Discover tools"), hint: "Query container for available tools" });
  }
  options.push({ value: "info", label: "View details" });
  options.push({ value: "delete", label: noumena.purpleDim("Delete service") });

  const action = await p.select({
    message: "Action:",
    options,
  });

  if (p.isCancel(action) || action === "back") {
    return;
  }

  const s = p.spinner();

  if (action === "enable" || action === "disable") {
    const newEnabled = action === "enable";
    
    const confirmed = await p.confirm({
      message: `${newEnabled ? "Enable" : "Disable"} ${service.displayName}?`,
      initialValue: true,
    });

    if (p.isCancel(confirmed) || !confirmed) {
      return;
    }

    s.start(`${newEnabled ? "Enabling" : "Disabling"}...`);
    const success = setServiceEnabled(service.name, newEnabled);
    
    if (success) {
      try {
        await reloadGatewayConfig();
        s.stop(noumena.success(`${service.displayName}: ${newEnabled ? "Enabled" : "Disabled"}`));
      } catch {
        s.stop(noumena.success(`${service.displayName}: ${newEnabled ? "Enabled" : "Disabled"}`));
      }

      try {
        await syncServiceWithNpl(service.name, newEnabled);
        p.log.success("NPL policy updated");
      } catch {
        p.log.warn("NPL sync failed - policy may not reflect this change");
      }

      // After enabling, go to tool selection
      if (newEnabled && service.tools.length > 0) {
        p.log.info("Now select which tools to enable:");
        await manageToolsForService(service);
      }
    } else {
      s.stop(noumena.purpleDim("Failed"));
    }
  } else if (action === "pull" && imageName) {
    s.start(`Pulling ${imageName}...`);
    try {
      execSync(`docker pull ${imageName}`, { stdio: "pipe" });
      s.stop(noumena.success("Image pulled"));
    } catch {
      s.stop(noumena.purpleDim("Failed to pull"));
    }
  } else if (action === "start" && imageName) {
    const containerName = `mcp-${service.name}`;
    s.start("Starting container...");
    const result = startContainer(imageName, containerName);
    if (result.success) {
      s.stop(noumena.success("Container started"));
    } else {
      s.stop(noumena.purpleDim(`Failed: ${result.error}`));
    }
  } else if (action === "stop") {
    const containerName = `mcp-${service.name}`;
    s.start("Stopping container...");
    const result = stopContainer(containerName);
    if (result.success) {
      s.stop(noumena.success("Container stopped"));
    } else {
      s.stop(noumena.purpleDim(`Failed: ${result.error}`));
    }
  } else if (action === "logs") {
    const containerName = `mcp-${service.name}`;
    try {
      const logs = execSync(`docker logs --tail 30 ${containerName}`, { encoding: "utf-8" });
      console.log();
      console.log(noumena.purple("  Last 30 log lines:"));
      console.log(noumena.textDim(logs || "(no logs)"));
    } catch {
      p.log.warn("Failed to get logs");
    }
  } else if (action === "discover" && imageName) {
    const toolCount = await discoverAndSaveTools(service.name, imageName);
    if (toolCount > 0) {
      p.log.info("All discovered tools are disabled by default. Use 'Manage tools' to enable them.");
    }
  } else if (action === "tools") {
    await manageToolsForService(service);
  } else if (action === "info") {
    await viewServiceInfo(service);
  } else if (action === "delete") {
    const confirmed = await p.confirm({
      message: `Delete ${noumena.purple(service.displayName)}? This cannot be undone.`,
      initialValue: false,
    });

    if (!p.isCancel(confirmed) && confirmed) {
      const deleteSpinner = p.spinner();
      
      // Step 1: Stop container if running (for Docker-based services)
      if (isDocker && imageName) {
        const containerName = `mcp-${service.name}`;
        const status = getContainerStatus(imageName);
        if (status.running) {
          deleteSpinner.start("Stopping container...");
          const result = stopContainer(containerName);
          if (result.success) {
            deleteSpinner.stop(noumena.success("Container stopped"));
          } else {
            deleteSpinner.stop(noumena.warning("Could not stop container (may already be stopped)"));
          }
        }
      }

      // Step 2: Disable in NPL (remove from allowed services)
      deleteSpinner.start("Removing from NPL policy...");
      try {
        await syncServiceWithNpl(service.name, false);
        deleteSpinner.stop(noumena.success("Removed from NPL policy"));
      } catch {
        deleteSpinner.stop(noumena.textDim("NPL sync skipped (not configured)"));
      }

      // Step 3: Remove from config
      deleteSpinner.start("Removing from configuration...");
      const success = removeService(service.name);
      if (success) {
        deleteSpinner.stop(noumena.success("Removed from configuration"));
        try {
          await reloadGatewayConfig();
          p.log.success(`${service.displayName} deleted successfully`);
        } catch {
          p.log.success(`${service.displayName} deleted (Gateway reload pending)`);
        }
      } else {
        deleteSpinner.stop(noumena.purpleDim("Failed to remove from configuration"));
      }
    }
  }
}

/**
 * Manage tools for a specific service
 */
async function manageToolsForService(service: ServiceDefinition): Promise<void> {
  let freshService = loadConfig().services.find(s => s.name === service.name);
  if (!freshService) {
    p.log.warn("Service not found");
    return;
  }

  if (freshService.tools.length === 0) {
    p.log.warn("No tools configured for this service");
    return;
  }

  while (true) {
    // Refresh service data
    freshService = loadConfig().services.find(s => s.name === service.name);
    if (!freshService) break;

    const enabledCount = freshService.tools.filter(t => t.enabled !== false).length;
    
    console.log();
    console.log(noumena.purple(`  ${freshService.displayName} - Tools`));
    console.log(noumena.textDim(`  ${enabledCount}/${freshService.tools.length} enabled`));
    console.log();

    // First, choose action
    const action = await p.select({
      message: "What do you want to do?",
      options: [
        { value: "back" as const, label: noumena.textDim("← Back") },
        { value: "enable" as const, label: noumena.success("Enable tools"), hint: "Pick tools with SPACE, then ENTER" },
        { value: "disable" as const, label: noumena.warning("Disable tools"), hint: "Pick tools with SPACE, then ENTER" },
        { value: "enable_all" as const, label: "Enable all", hint: "Enable all tools at once" },
        { value: "disable_all" as const, label: "Disable all", hint: "Disable all tools at once" },
      ],
    });

    if (p.isCancel(action) || action === "back") {
      break;
    }

    if (action === "enable_all") {
      for (const tool of freshService.tools) {
        setToolEnabled(freshService.name, tool.name, true);
      }
      p.log.success("All tools enabled");
      continue;
    }

    if (action === "disable_all") {
      for (const tool of freshService.tools) {
        setToolEnabled(freshService.name, tool.name, false);
      }
      p.log.success("All tools disabled");
      continue;
    }

    // Filter tools based on action
    const toolsToShow = action === "enable" 
      ? freshService.tools.filter(t => t.enabled === false)
      : freshService.tools.filter(t => t.enabled !== false);

    if (toolsToShow.length === 0) {
      p.log.info(action === "enable" ? "All tools are already enabled" : "All tools are already disabled");
      continue;
    }

    // Instructions
    console.log();
    console.log(noumena.textDim("  Use ") + noumena.purple("SPACE") + noumena.textDim(" to select/deselect tools"));
    console.log(noumena.textDim("  Press ") + noumena.purple("ENTER") + noumena.textDim(` to ${action} selected tools`));
    console.log();

    // Multi-select tools
    const selectedTools = await p.multiselect({
      message: `Select tools to ${action}:`,
      options: toolsToShow.map(tool => ({
        value: tool.name,
        label: tool.name,
        hint: tool.description.substring(0, 50),
      })),
      required: false,
    });

    if (p.isCancel(selectedTools) || selectedTools.length === 0) {
      continue;
    }

    // Apply changes
    const newEnabled = action === "enable";
    for (const toolName of selectedTools) {
      setToolEnabled(freshService.name, toolName, newEnabled);
    }
    
    p.log.success(`${selectedTools.length} tool(s) ${newEnabled ? "enabled" : "disabled"}`);
  }
}

/**
 * View detailed service info
 */
async function viewServiceInfo(service: ServiceDefinition): Promise<void> {
  const enabledTools = service.tools.filter(t => t.enabled !== false).length;

  console.log();
  console.log(noumena.purple(`  ${service.displayName}`));
  console.log();
  console.log(noumena.textDim("  Name: ") + service.name);
  console.log(noumena.textDim("  Type: ") + getTypeLabel(service.type));
  console.log(noumena.textDim("  Enabled: ") + (service.enabled ? noumena.success("Yes") : noumena.grayDim("No")));
  console.log(noumena.textDim("  Description: ") + service.description);
  
  if (service.command) {
    console.log(noumena.textDim("  Command: ") + service.command);
  }
  if (service.endpoint) {
    console.log(noumena.textDim("  Endpoint: ") + service.endpoint);
  }

  console.log();
  console.log(noumena.purple(`  Tools (${enabledTools}/${service.tools.length} enabled):`));
  
  for (const tool of service.tools) {
    const enabled = tool.enabled !== false;
    const status = enabled ? noumena.success("✓") : noumena.grayDim("–");
    console.log(`    ${status} ${tool.name}`);
    console.log(noumena.textDim(`      ${tool.description.substring(0, 60)}...`));
  }
  console.log();

  await p.confirm({ message: "Press Enter to continue", initialValue: true });
}

/**
 * Discover tools from an MCP container and save them to config.
 * Returns the number of tools discovered.
 */
async function discoverAndSaveTools(serviceName: string, imageName: string): Promise<number> {
  const s = p.spinner();
  s.start(`Discovering tools from ${imageName}...`);

  const result = discoverToolsFromContainer(imageName);

  if (!result.success || result.tools.length === 0) {
    s.stop(noumena.warning(
      result.error
        ? `Could not discover tools: ${result.error}`
        : "No tools found (container may require credentials)"
    ));
    return 0;
  }

  // Convert discovered tools to config format (all disabled by default)
  const toolDefs = discoveredToToolDefinitions(result.tools, false);

  // Save to config
  const saved = updateServiceTools(serviceName, toolDefs);
  if (saved) {
    const serverLabel = result.serverInfo
      ? ` (${result.serverInfo.name} v${result.serverInfo.version})`
      : "";
    s.stop(noumena.success(`Discovered ${toolDefs.length} tool(s)${serverLabel}`));

    // Show a summary of discovered tools
    for (const tool of toolDefs) {
      const desc = tool.description.length > 60 
        ? tool.description.substring(0, 57) + "..." 
        : tool.description;
      console.log(noumena.textDim(`    ${noumena.grayDim("–")} ${tool.name}: ${desc}`));
    }
    console.log();
  } else {
    s.stop(noumena.warning("Failed to save discovered tools"));
  }

  return toolDefs.length;
}

/**
 * Search Docker Hub for MCP servers
 */
async function searchDockerHubFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Search Docker Hub"));
  console.log(noumena.textDim("  Find MCP servers from the official mcp/* namespace"));
  console.log();

  // Search query input
  const query = await p.text({
    message: "Search term (leave empty for all):",
    placeholder: "e.g., fetch, github, slack, filesystem...",
  });

  if (p.isCancel(query)) {
    return;
  }

  const searchTerm = query?.trim() || undefined;
  
  const s = p.spinner();
  s.start(searchTerm ? `Searching for "${searchTerm}"...` : "Loading all MCP servers...");

  let servers;
  try {
    servers = await discoverMcpServers(searchTerm, 100);
  } catch (err) {
    s.stop(noumena.purpleDim("Failed to search Docker Hub"));
    p.log.warn(`Error: ${err}`);
    p.log.info("Check your internet connection and try again");
    return;
  }
  s.stop();

  if (servers.length === 0) {
    p.log.warn(searchTerm ? `No servers found matching "${searchTerm}"` : "No MCP servers found");
    return;
  }

  p.log.info(`Found ${servers.length} MCP servers`);

  // Get current services to check what's already added
  const config = loadConfig();
  const existingNames = new Set(config.services.map(s => s.name));

  // Format pull count
  const formatPulls = (count: number): string => {
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
    if (count >= 1000) return `${Math.round(count / 1000)}K`;
    return count.toString();
  };

  // Show all results (clack handles scrolling)
  const selected = await p.select({
    message: `Select an MCP server to add (${servers.length} available):`,
    options: [
      { value: "back", label: noumena.textDim("← Back"), hint: "" },
      ...servers.map(server => {
        const added = existingNames.has(server.name);
        const pulls = formatPulls(server.pullCount);
        return {
          value: server.name,
          label: `mcp/${server.name}` + (added ? noumena.textDim(" [added]") : ""),
          hint: `${pulls} pulls`,
        };
      }),
    ],
  });

  if (p.isCancel(selected) || selected === "back") {
    return;
  }

  if (existingNames.has(selected)) {
    p.log.warn(`${selected} is already configured`);
    return;
  }

  const server = servers.find(s => s.name === selected);
  if (!server) return;

  // Ask about pulling the image
  const shouldPull = await p.confirm({
    message: "Pull Docker image now?",
    initialValue: true,
  });

  if (!p.isCancel(shouldPull) && shouldPull) {
    const s = p.spinner();
    s.start(`Pulling mcp/${server.name}...`);
    try {
      execSync(`docker pull mcp/${server.name}`, { stdio: "pipe" });
      s.stop(noumena.success("Image pulled successfully"));
    } catch {
      s.stop(noumena.purpleDim("Failed to pull image - you can pull manually later"));
    }
  }

  // Create service definition with placeholder tool (will be replaced by discovery)
  const newService: ServiceDefinition = {
    name: server.name,
    displayName: server.name.split("-").map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" "),
    type: "MCP_STDIO",
    enabled: false,
    command: `docker run -i --rm mcp/${server.name}`,
    args: [],
    requiresCredentials: false,
    description: server.description,
    tools: [],
  };

  const success = addService(newService);
  if (!success) {
    p.log.warn("Failed to add service");
    return;
  }

  p.log.success(`Added: mcp/${server.name}`);

  // Auto-discover tools from the container
  const imagePulled = !p.isCancel(shouldPull) && shouldPull;
  if (imagePulled || imageExists(`mcp/${server.name}`)) {
    const toolCount = await discoverAndSaveTools(server.name, `mcp/${server.name}`);
    if (toolCount === 0) {
      // Add a default placeholder if discovery failed
      const { addTool } = await import("./lib/config.js");
      addTool(server.name, {
        name: `${server.name}_default`,
        description: `Default tool for ${server.name} - run 'Discover tools' to find actual tools`,
        inputSchema: { type: "object", properties: {}, required: [] },
        enabled: false,
      });
    }
  } else {
    // Image not pulled - add placeholder
    const { addTool } = await import("./lib/config.js");
    addTool(server.name, {
      name: `${server.name}_default`,
      description: `Default tool for ${server.name} - pull image and run 'Discover tools'`,
      inputSchema: { type: "object", properties: {}, required: [] },
      enabled: false,
    });
  }

  p.log.info("Service is disabled by default. Select it to enable and configure tools.");
  
  try {
    await reloadGatewayConfig();
  } catch {
    // Ignore
  }
}

/**
 * Add a custom Docker image (local or private registry)
 */
async function addCustomImageFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add Custom MCP Service"));
  console.log(noumena.textDim("  Add a Docker-based MCP server from:"));
  console.log(noumena.textDim("  • Local image (my-mcp-server)"));
  console.log(noumena.textDim("  • Private registry (ghcr.io/org/mcp-server)"));
  console.log(noumena.textDim("  • Any Docker registry"));
  console.log();

  const imageName = await p.text({
    message: "Enter Docker image name:",
    placeholder: "e.g., my-mcp-server or ghcr.io/myorg/mcp-server:latest",
    validate: (value) => {
      if (!value || value.trim().length === 0) {
        return "Image name is required";
      }
      // Basic validation: no spaces, reasonable characters
      if (/\s/.test(value)) {
        return "Image name cannot contain spaces";
      }
      return undefined;
    },
  });

  if (p.isCancel(imageName)) {
    return;
  }

  const imageNameStr = imageName.trim();

  // Extract a service name from the image
  // e.g., "ghcr.io/myorg/my-mcp-server:v1" -> "my-mcp-server"
  const extractServiceName = (img: string): string => {
    // Remove tag
    const withoutTag = img.split(":")[0];
    // Get last part after /
    const parts = withoutTag.split("/");
    return parts[parts.length - 1];
  };

  const suggestedName = extractServiceName(imageNameStr);

  const serviceName = await p.text({
    message: "Service name:",
    initialValue: suggestedName,
    placeholder: "e.g., my-mcp-server",
    validate: (value) => {
      if (!value || value.trim().length === 0) {
        return "Service name is required";
      }
      if (!/^[a-z0-9_-]+$/.test(value)) {
        return "Use lowercase letters, numbers, hyphens, and underscores only";
      }
      // Check if already exists
      const config = loadConfig();
      if (config.services.some(s => s.name === value)) {
        return `A service named '${value}' already exists`;
      }
      return undefined;
    },
  });

  if (p.isCancel(serviceName)) {
    return;
  }

  const serviceNameStr = serviceName.trim();

  const displayName = await p.text({
    message: "Display name:",
    initialValue: serviceNameStr.split("-").map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" "),
    placeholder: "Human-readable name for the service",
  });

  if (p.isCancel(displayName)) {
    return;
  }

  const description = await p.text({
    message: "Description:",
    placeholder: "Brief description of what this MCP service does",
    initialValue: `Custom MCP service: ${displayName}`,
  });

  if (p.isCancel(description)) {
    return;
  }

  // Check if image exists locally
  const localImageExists = imageExists(imageNameStr);
  
  if (!localImageExists) {
    console.log();
    console.log(noumena.warning(`  Image '${imageNameStr}' not found locally.`));
    console.log();
    
    const shouldPull = await p.confirm({
      message: "Try to pull the image now?",
      initialValue: true,
    });

    if (!p.isCancel(shouldPull) && shouldPull) {
      const s = p.spinner();
      s.start(`Pulling ${imageNameStr}...`);
      try {
        execSync(`docker pull ${imageNameStr}`, { stdio: "pipe" });
        s.stop(noumena.success("Image pulled successfully"));
      } catch (err) {
        s.stop(noumena.purpleDim("Failed to pull image"));
        
        const continueAnyway = await p.confirm({
          message: "Continue adding service anyway? (image must be available when used)",
          initialValue: false,
        });
        
        if (p.isCancel(continueAnyway) || !continueAnyway) {
          p.log.info("Cancelled");
          return;
        }
      }
    }
  } else {
    p.log.success(`Image '${imageNameStr}' found locally`);
  }

  // Create service definition
  const newService: ServiceDefinition = {
    name: serviceNameStr,
    displayName: String(displayName).trim(),
    type: "MCP_STDIO",
    enabled: false,
    command: `docker run -i --rm ${imageNameStr}`,
    args: [],
    requiresCredentials: false,
    description: String(description).trim(),
    tools: [],
  };

  const success = addService(newService);
  if (!success) {
    p.log.warn("Failed to add service");
    return;
  }

  p.log.success(`Added: ${displayName}`);

  // Auto-discover tools from the container
  if (imageExists(imageNameStr)) {
    const toolCount = await discoverAndSaveTools(serviceNameStr, imageNameStr);
    if (toolCount === 0) {
      const { addTool } = await import("./lib/config.js");
      addTool(serviceNameStr, {
        name: `${serviceNameStr}_default`,
        description: `Default tool for ${serviceNameStr} - run 'Discover tools' to find actual tools`,
        inputSchema: { type: "object", properties: {}, required: [] },
        enabled: false,
      });
    }
  } else {
    const { addTool } = await import("./lib/config.js");
    addTool(serviceNameStr, {
      name: `${serviceNameStr}_default`,
      description: `Default tool for ${serviceNameStr} - pull image and run 'Discover tools'`,
      inputSchema: { type: "object", properties: {}, required: [] },
      enabled: false,
    });
  }

  p.log.info("Service is disabled by default. Select it to enable and configure tools.");
  
  try {
    await reloadGatewayConfig();
  } catch {
    // Ignore
  }
}

/**
 * Reload Gateway configuration
 */
async function reloadGatewayFlow(): Promise<void> {
  const s = p.spinner();
  s.start("Reloading Gateway configuration...");

  try {
    await reloadGatewayConfig();
    s.stop(noumena.success("Gateway configuration reloaded"));
  } catch {
    s.stop(noumena.purpleDim("Failed to reload Gateway config"));
  }
}

/**
 * Main entry point
 */
async function main() {
  console.clear();
  
  p.intro(noumena.purple("◆ NOUMENA MCP Gateway Wizard"));

  // Step 1: Admin login (credentials stored in memory only)
  const loggedIn = await adminLogin();
  if (!loggedIn) {
    p.outro(noumena.purpleDim("Login required. Goodbye."));
    return;
  }

  // Step 2: Check Gateway connection
  const s = p.spinner();
  s.start("Connecting to Gateway...");
  
  const connected = await checkGatewayHealth();
  if (connected) {
    s.stop(noumena.success("Gateway connected"));
  } else {
    s.stop(noumena.warning("Gateway not available (running in offline mode)"));
  }

  // Step 3: Check NPL bootstrap status
  const bs = p.spinner();
  bs.start("Checking NPL status...");
  const bootstrapped = await isNplBootstrapped();
  if (bootstrapped) {
    bs.stop(noumena.success("NPL bootstrapped"));
  } else {
    bs.stop(noumena.warning("NPL not bootstrapped"));
    p.log.warn("ServiceRegistry or ToolExecutionPolicy not found.");
    p.log.info("Use 'NPL Bootstrap' from the menu to set up.");
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

main().catch(err => {
  console.error("Fatal error:", err);
  process.exit(1);
});
