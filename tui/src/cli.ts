#!/usr/bin/env node
/**
 * NOUMENA MCP Gateway Wizard
 * 
 * Interactive CLI for managing MCP Gateway services.
 * Uses @clack/prompts for a beautiful terminal experience.
 */

import * as p from "@clack/prompts";
import chalk from "chalk";
import { execSync, spawnSync } from "child_process";
import { readFileSync } from "fs";
import * as path from "path";
import { 
  loadConfig, 
  setServiceEnabled, 
  removeService,
  addService,
  setToolEnabled,
  updateServiceTools,
  getAllUsers,
  getUser,
  addUser,
  updateUser,
  removeUser as removeUserFromConfig,
  grantToolToUser,
  revokeToolFromUser,
  grantAllToolsToUser,
  revokeServiceFromUser,
  type ServiceDefinition,
  type UserToolAccess,
  getConfigPath,
  loadCredentialsConfig,
  saveCredentialsConfig,
  addCredentialMapping,
  setServiceCredential,
  getServiceCredential,
  hasCredentials,
} from "./lib/config.js";
import { 
  checkGatewayHealth, 
  reloadGatewayConfig, 
  syncServiceWithNpl,
  discoverMcpServers,
  imageExists,
  discoverToolsFromContainer,
  discoveredToToolDefinitions,
  setAdminCredentials,
  validateCredentials,
  bootstrapNpl,
  isNplBootstrapped,
  listKeycloakUsers,
  createKeycloakUser,
  deleteKeycloakUser,
  registerUserInNpl,
  removeUserFromNpl,
  grantToolInNpl,
  revokeToolInNpl,
  grantAllToolsForServiceInNpl,
  revokeServiceInNpl,
  getUserAccessFromNpl,
  syncUserAccessToNpl,
  getKeycloakToken,
  findUserToolAccess,
  type KeycloakUser,
  storeSecretInVault,
  getSecretFromVault,
  testCredentialInjection,
} from "./lib/api.js";

// Noumena color palette - optimized for dark terminal backgrounds
const noumena = {
  // Brand colors (section headers only)
  purple: chalk.hex("#A78BFA"),      // Lighter purple for better readability
  purpleDim: chalk.hex("#8B5CF6"),   // Medium purple
  
  // Interactive elements
  accent: chalk.hex("#60A5FA"),      // Blue for actionable items (+ Add buttons)
  accentBright: chalk.hex("#93C5FD"), // Brighter blue for emphasis
  
  // Status colors
  success: chalk.hex("#34D399"),     // Brighter green for success
  warning: chalk.hex("#FBBF24"),     // Brighter amber for warnings
  error: chalk.hex("#F87171"),       // Red for errors
  
  // Text colors
  text: chalk.hex("#F3F4F6"),        // Brighter white for main text
  textDim: chalk.hex("#D1D5DB"),     // Lighter gray for secondary text
  gray: chalk.hex("#9CA3AF"),        // Medium gray
  grayDim: chalk.hex("#6B7280"),     // Darker gray for disabled items
};

// Track whether services.yaml has been modified since last Gateway reload
let configDirty = false;

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
 * Import from YAML - sync services.yaml to NPL (V3 Architecture).
 * 
 * This is a DECLARATIVE sync with confirmation:
 * - services.yaml defines the desired state (services, tools, users)
 * - This function makes NPL match that state
 * - ADDS services/tools/users from YAML (if not in NPL)
 * - REMOVES services/tools/users from NPL (if not in YAML)
 * 
 * KEYCLOAK SEPARATION: This does NOT create/delete Keycloak users!
 * - Keycloak users must be managed separately (Terraform or Keycloak Admin UI)
 * - This only syncs NPL permissions for existing Keycloak users
 * 
 * V3 Architecture:
 *   1. ServiceRegistry — tracks which services are enabled
 *   2. ToolPolicy (per service) — per-tool access control (global)
 *   3. UserRegistry — tracks registered users/agents
 *   4. UserToolAccess (per user) — per-user tool access control
 */
async function importFromYamlFlow(skipConfirmation: boolean = false): Promise<void> {
  if (!skipConfirmation) {
    console.log();
    console.log(noumena.purple("  Import from YAML → NPL"));
    console.log();
    console.log(noumena.textDim("  📄 services.yaml defines the desired state"));
    console.log(noumena.textDim("  ✅ Adds services/tools/users from YAML to NPL"));
    console.log(noumena.textDim("  🧹 Removes services/tools/users from NPL not in YAML"));
    console.log();
    console.log(noumena.warning("  ⚠  DESTRUCTIVE: This will overwrite NPL state with YAML!"));
    console.log(noumena.warning("  ⚠  DOES NOT touch Keycloak - only syncs NPL permissions!"));
    console.log();

    const confirm = await p.confirm({
      message: "Proceed with import? This will make NPL match services.yaml.",
      initialValue: false,
    });

    if (p.isCancel(confirm) || !confirm) {
      p.log.info("Import cancelled");
      return;
    }
  }

  const s = p.spinner();
  s.start("Importing from YAML to NPL...");

  try {
    const result = await bootstrapNpl();
    s.stop(noumena.success("Import completed"));

    // Service-level bootstrap
    if (result.registryCreated) {
      p.log.success("Created ServiceRegistry");
    } else {
      p.log.info("ServiceRegistry already exists");
    }

    if (result.policiesCreated.length > 0) {
      p.log.success(`Created ToolPolicy for: ${result.policiesCreated.join(", ")}`);
    } else {
      p.log.info("All ToolPolicy instances already exist");
    }

    if (result.servicesEnabled.length > 0) {
      p.log.info(`Enabled services: ${result.servicesEnabled.join(", ")}`);
    } else {
      p.log.info("No services enabled in services.yaml");
    }

    // User-level bootstrap
    if (result.userRegistryCreated) {
      p.log.success("Created UserRegistry");
    } else {
      p.log.info("UserRegistry already exists");
    }

    if (result.usersSynced.length > 0) {
      p.log.success(`Synced ${result.usersSynced.length} user(s): ${result.usersSynced.join(", ")}`);
    } else {
      p.log.info("No users configured in services.yaml");
    }

    console.log();
    console.log(noumena.success("  ✓ NPL now matches services.yaml"));

    // Auto-reload Gateway to apply NPL changes
    s.start("Reloading Gateway...");
    try {
      await reloadGatewayConfig();
      s.stop(noumena.success("Gateway reloaded"));
      configDirty = false;
    } catch (err: any) {
      s.stop(noumena.purpleDim("Gateway reload failed"));
      p.log.warn("NPL updated but Gateway reload failed - use 'Reload Gateway' manually");
      configDirty = true;
    }

    // Pause so user can read the output
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
  } catch (error) {
    s.stop(noumena.purpleDim("Import failed"));
    p.log.error(`${error}`);
    p.log.info("Make sure the NPL Engine is running and Keycloak is provisioned.");
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
    configDirty = true;
  }
}

/**
 * Reset to Defaults - factory reset for the Gateway.
 * 
 * This will:
 * 1. Re-provision Keycloak (runs Terraform)
 * 2. Query Keycloak for actual user IDs
 * 3. Restore default services.yaml with actual IDs
 * 4. Import services.yaml to NPL
 */
async function resetToDefaultsFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Reset to Defaults (Factory Reset)"));
  console.log();
  console.log(noumena.textDim("  🔄 Re-provisions Keycloak (Terraform)"));
  console.log(noumena.textDim("  🔍 Queries Keycloak for user IDs"));
  console.log(noumena.textDim("  📄 Restores default services.yaml"));
  console.log(noumena.textDim("  🔄 Imports services.yaml to NPL"));
  console.log();
  console.log(noumena.warning("  ⚠  DESTRUCTIVE: This will delete all users and configurations!"));
  console.log(noumena.warning("  ⚠  Default users: admin, gateway, jarvis, alice, bob"));
  console.log();

  const confirm = await p.confirm({
    message: "Proceed with factory reset? This cannot be undone.",
    initialValue: false,
  });

  if (p.isCancel(confirm) || !confirm) {
    p.log.info("Reset cancelled");
    return;
  }

  // Step 1: Re-provision Keycloak via Docker Compose
  const kcSpinner = p.spinner();
  kcSpinner.start("Re-provisioning Keycloak (Terraform)...");
  
  try {
    // Remove existing keycloak-provisioning container and volume
    execSync(
      "docker compose rm -sf keycloak-provisioning && docker volume rm gateway_keycloak-provisioning 2>/dev/null || true",
      { 
        encoding: "utf-8",
        cwd: path.join(process.cwd(), "../deployments"),
        stdio: ["pipe", "pipe", "pipe"]
      }
    );
    
    // Re-run provisioning service (this will re-apply Terraform with env vars from docker-compose.yml)
    execSync(
      "docker compose up keycloak-provisioning --abort-on-container-exit",
      { 
        encoding: "utf-8",
        cwd: path.join(process.cwd(), "../deployments"),
        stdio: ["pipe", "pipe", "pipe"]
      }
    );
    kcSpinner.stop(noumena.success("Keycloak re-provisioned"));
  } catch (err: any) {
    kcSpinner.stop(noumena.purpleDim("Keycloak provisioning failed"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.info("Try: cd deployments && docker compose up keycloak-provisioning");
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
    return;
  }

  // Step 2: Query Keycloak for actual user IDs
  const userIdSpinner = p.spinner();
  userIdSpinner.start("Querying Keycloak for user IDs...");
  
  let userIdMap: Record<string, string> = {};
  try {
    const kcUsers = await listKeycloakUsers();
    
    // Map username -> keycloakId
    for (const user of kcUsers) {
      if (user.username && user.id) {
        userIdMap[user.username] = user.id;
      }
    }
    
    userIdSpinner.stop(noumena.success("User IDs fetched"));
    p.log.info(`Found: jarvis=${userIdMap['jarvis']?.substring(0, 8)}..., alice=${userIdMap['alice']?.substring(0, 8)}..., bob=${userIdMap['bob']?.substring(0, 8)}...`);
  } catch (err: any) {
    userIdSpinner.stop(noumena.purpleDim("Failed to query Keycloak"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.info("You may need to wait for Keycloak to fully start, then retry.");
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
    return;
  }

  // Step 3: Restore default services.yaml with actual IDs
  const yamlSpinner = p.spinner();
  yamlSpinner.start("Restoring default services.yaml...");
  
  try {
    const configPath = getConfigPath();
    const defaultConfigPath = configPath.replace("services.yaml", "services.yaml.default");
    
    // Read default config
    let defaultYaml = readFileSync(defaultConfigPath, "utf-8");
    
    // Replace placeholder IDs with actual Keycloak IDs
    if (userIdMap['jarvis']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_JARVIS_ID/g, userIdMap['jarvis']);
    }
    if (userIdMap['alice']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_ALICE_ID/g, userIdMap['alice']);
    }
    if (userIdMap['bob']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_BOB_ID/g, userIdMap['bob']);
    }
    
    // Write to services.yaml
    const fs = await import("fs/promises");
    await fs.writeFile(configPath, defaultYaml, "utf-8");
    
    yamlSpinner.stop(noumena.success("Default services.yaml restored with actual user IDs"));
  } catch (err: any) {
    yamlSpinner.stop(noumena.purpleDim("Failed to restore default services.yaml"));
    p.log.error(`Error: ${err.message || err}`);
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
    return;
  }

  // Step 4: Import services.yaml to NPL
  const nplSpinner = p.spinner();
  nplSpinner.start("Importing default config to NPL...");
  
  try {
    const result = await bootstrapNpl();
    nplSpinner.stop(noumena.success("NPL imported"));
    
    p.log.success(`✓ Synced ${result.usersSynced.length} default user(s): ${result.usersSynced.join(", ")}`);
  } catch (err: any) {
    nplSpinner.stop(noumena.purpleDim("NPL import failed"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.info("You can retry import from the System menu.");
  }

  // Step 5: Reload Gateway
  nplSpinner.start("Reloading Gateway...");
  try {
    await reloadGatewayConfig();
    nplSpinner.stop(noumena.success("Gateway reloaded"));
    configDirty = false;
  } catch (err: any) {
    nplSpinner.stop(noumena.purpleDim("Gateway reload failed"));
    p.log.warn("You may need to restart Gateway manually");
    configDirty = true;
  }

  // Done!
  console.log();
  console.log(noumena.success("  ✓ Factory reset complete!"));
  console.log(noumena.textDim("  Default users: jarvis, alice, bob"));
  console.log(noumena.textDim("  Default password: admin (change in Keycloak)"));
  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

/**
 * Configuration Manager - centralized import/export/backup management
 */
async function configurationManagerFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");
  const configPath = getConfigPath();
  const configDir = path.dirname(configPath);
  const nplReady = await isNplBootstrapped();

  while (true) {
    console.log();
    console.log(noumena.purple("  Configuration Manager"));
    console.log(noumena.textDim("  Manage services.yaml: Import, Export, Backups, Restore"));
    console.log();

    // List available backups
    let backupFiles: string[] = [];
    try {
      const files = await fs.readdir(configDir);
      backupFiles = files
        .filter(f => f.startsWith("services.yaml.") && (f.endsWith(".backup") || f === "services.yaml.default"))
        .sort()
        .reverse();
    } catch {
      // Ignore errors
    }

    const backupCount = backupFiles.filter(f => f.endsWith(".backup")).length;
    const hasFactory = backupFiles.includes("services.yaml.default");

    const action = await p.select({
      message: "Select action:",
      options: [
        { value: "back", label: noumena.textDim("← Back") },
        { value: "---hdr-current", label: noumena.purple("── Current Configuration ──"), hint: "" },
        { value: "manage-current", label: "  Manage services.yaml", hint: "View, Edit, Export, Apply" },
        { value: "---hdr-import", label: noumena.purple("── Import ──"), hint: "" },
        { value: "import-current", label: `  Import from services.yaml  ${nplReady ? noumena.success("✓") : noumena.warning("⚠")}`, hint: "YAML → NPL (sync current file)" },
        { value: "import-file", label: "  Import from file...", hint: "Choose a YAML file to import" },
        { value: "---hdr-backups", label: noumena.purple("── Backups ──"), hint: "" },
        { value: "view-backups", label: `  Browse backups  ${backupCount > 0 ? `(${backupCount})` : noumena.grayDim("(0)")}`, hint: "View and restore" },
        { value: "view-factory", label: `  View factory defaults  ${hasFactory ? "" : noumena.grayDim("(n/a)")}`, hint: "services.yaml.default" },
        { value: "---hdr-reset", label: noumena.purple("── Reset ──"), hint: "" },
        { value: "reset", label: "  Reset to Factory Defaults", hint: "Full reset: Keycloak + NPL + Config" },
      ],
    });

    if (p.isCancel(action) || action === "back") {
      return;
    }

    if (typeof action === "string" && action.startsWith("---")) {
      continue;
    }

    if (action === "manage-current") {
      await manageCurrentConfigFlow();
    } else if (action === "import-current") {
      await importFromYamlFlow();
    } else if (action === "import-file") {
      await importFromFileFlow();
    } else if (action === "view-factory" && hasFactory) {
      await viewFactoryDefaultsFlow();
    } else if (action === "view-backups") {
      if (backupCount === 0) {
        p.log.info("No backups found. Use 'Export current config' to create backups.");
      } else {
        await browseBackupsFlow(backupFiles.filter(f => f.endsWith(".backup")));
      }
    } else if (action === "reset") {
      await resetToDefaultsFlow();
    }
  }
}

/**
 * Manage Current Configuration (services.yaml) - consolidated operations
 */
async function manageCurrentConfigFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");
  const configPath = getConfigPath();

  while (true) {
    console.log();
    console.log(noumena.purple("  Manage services.yaml"));
    console.log(noumena.textDim("  View, Edit, Export, and Apply changes to current configuration"));
    console.log();

    // Check file stats
    let fileSize = "unknown";
    let lastModified = "unknown";
    try {
      const stats = await fs.stat(configPath);
      fileSize = `${Math.round(stats.size / 1024)}KB`;
      lastModified = stats.mtime.toLocaleString();
    } catch {
      // Ignore
    }

    console.log(noumena.textDim(`  File: ${path.basename(configPath)}`));
    console.log(noumena.textDim(`  Size: ${fileSize}`));
    console.log(noumena.textDim(`  Last modified: ${lastModified}`));
    console.log();

    const action = await p.select({
      message: "Select action:",
      options: [
        { value: "back", label: noumena.textDim("← Back") },
        { value: "view", label: "  View current config", hint: "Display services.yaml contents" },
        { value: "export", label: "  Export (create backup)", hint: "Save timestamped backup" },
        { value: "edit", label: noumena.warning("  Edit and apply"), hint: "Edit in $EDITOR, confirm, then apply to NPL + Gateway" },
        { value: "restore-factory", label: noumena.purpleDim("  Restore to factory defaults"), hint: "Reset to services.yaml.default" },
      ],
    });

    if (p.isCancel(action) || action === "back") {
      return;
    }

    if (action === "view") {
      await viewConfigFlow();
    } else if (action === "export") {
      await exportToYamlFlow();
    } else if (action === "edit") {
      await editAndApplyConfigFlow();
    } else if (action === "restore-factory") {
      await restoreFactoryConfigFlow();
    }
  }
}

/**
 * Edit services.yaml and apply changes with confirmation
 */
async function editAndApplyConfigFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const configPath = getConfigPath();

  console.log();
  console.log(noumena.purple("  Edit and Apply Configuration"));
  console.log();
  console.log(noumena.textDim("  This will:"));
  console.log(noumena.textDim("    1. Open services.yaml in your editor"));
  console.log(noumena.textDim("    2. Ask for confirmation after editing"));
  console.log(noumena.textDim("    3. Import changes to NPL"));
  console.log(noumena.textDim("    4. Reload Gateway to apply"));
  console.log();
  console.log(noumena.warning("  ⚠  Changes will affect NPL and Gateway state!"));
  console.log();

  const proceed = await p.confirm({
    message: "Open editor?",
    initialValue: true,
  });

  if (p.isCancel(proceed) || !proceed) {
    p.log.info("Edit cancelled");
    return;
  }

  // Create backup before editing
  try {
    const backupPath = configPath + ".pre-edit.backup";
    const content = await fs.readFile(configPath, "utf-8");
    await fs.writeFile(backupPath, content, "utf-8");
    p.log.info(`Backup created: ${backupPath}`);
  } catch {
    p.log.warn("Could not create pre-edit backup");
  }

  // Open editor
  const editor = process.env.EDITOR || process.env.VISUAL || "nano";
  console.log();
  console.log(noumena.textDim(`  Opening ${editor}...`));
  console.log();

  try {
    execSync(`${editor} ${configPath}`, { stdio: "inherit" });
  } catch {
    p.log.error("Editor failed or was cancelled");
    return;
  }

  // Confirm changes
  console.log();
  console.log(noumena.warning("  ⚠  Apply changes to NPL and Gateway?"));
  console.log(noumena.textDim("  This will:"));
  console.log(noumena.textDim("    • Import services.yaml → NPL (declarative sync)"));
  console.log(noumena.textDim("    • Reload Gateway configuration"));
  console.log();

  const confirm = await p.confirm({
    message: "Apply changes?",
    initialValue: false,
  });

  if (p.isCancel(confirm) || !confirm) {
    p.log.info("Changes saved to file but NOT applied to NPL/Gateway");
    p.log.info("Use 'Import from services.yaml' to apply later");
    return;
  }

  // Apply changes
  const s = p.spinner();
  s.start("Importing to NPL...");

  try {
    await bootstrapNpl();
    s.stop(noumena.success("NPL updated"));
  } catch (err: any) {
    s.stop(noumena.purpleDim("NPL import failed"));
    p.log.error(`Error: ${err.message || err}`);
    return;
  }

  // Reload Gateway
  s.start("Reloading Gateway...");
  try {
    await reloadGatewayConfig();
    s.stop(noumena.success("Gateway reloaded"));
    configDirty = false;
  } catch (err: any) {
    s.stop(noumena.purpleDim("Gateway reload failed"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.info("Changes applied to NPL but Gateway may need manual restart");
    return;
  }

  console.log();
  console.log(noumena.success("  ✓ Configuration applied successfully!"));
  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

/**
 * Restore to factory defaults (services.yaml.default)
 */
async function restoreFactoryConfigFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const configPath = getConfigPath();
  const defaultPath = configPath.replace("services.yaml", "services.yaml.default");

  console.log();
  console.log(noumena.purple("  Restore to Factory Defaults"));
  console.log();
  console.log(noumena.textDim("  This will:"));
  console.log(noumena.textDim("    1. Replace services.yaml with services.yaml.default"));
  console.log(noumena.textDim("    2. Import to NPL (Jarvis, Alice, Bob)"));
  console.log(noumena.textDim("    3. Reload Gateway"));
  console.log();
  console.log(noumena.warning("  ⚠  DESTRUCTIVE: Current services.yaml will be replaced!"));
  console.log(noumena.warning("  ⚠  All custom services and users will be lost!"));
  console.log();
  console.log(noumena.textDim("  Note: This only restores the config file."));
  console.log(noumena.textDim("  For full reset (Keycloak + NPL), use 'Reset to Factory Defaults'."));
  console.log();

  const confirm = await p.confirm({
    message: "Restore factory defaults?",
    initialValue: false,
  });

  if (p.isCancel(confirm) || !confirm) {
    p.log.info("Restore cancelled");
    return;
  }

  // Query Keycloak for user IDs (same as full reset)
  const userIdSpinner = p.spinner();
  userIdSpinner.start("Querying Keycloak for user IDs...");
  
  let userIdMap: Record<string, string> = {};
  try {
    const kcUsers = await listKeycloakUsers();
    
    for (const user of kcUsers) {
      if (user.username && user.id) {
        userIdMap[user.username] = user.id;
      }
    }
    
    userIdSpinner.stop(noumena.success("User IDs fetched"));
  } catch (err: any) {
    userIdSpinner.stop(noumena.purpleDim("Failed to query Keycloak"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.warn("Proceeding with placeholder IDs - you may need to update manually");
  }

  // Restore default config
  const s = p.spinner();
  s.start("Restoring factory defaults...");

  try {
    let defaultYaml = await fs.readFile(defaultPath, "utf-8");
    
    // Replace placeholder IDs with actual Keycloak IDs
    if (userIdMap['jarvis']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_JARVIS_ID/g, userIdMap['jarvis']);
    }
    if (userIdMap['alice']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_ALICE_ID/g, userIdMap['alice']);
    }
    if (userIdMap['bob']) {
      defaultYaml = defaultYaml.replace(/PLACEHOLDER_BOB_ID/g, userIdMap['bob']);
    }
    
    await fs.writeFile(configPath, defaultYaml, "utf-8");
    s.stop(noumena.success("Factory defaults restored"));
  } catch (err: any) {
    s.stop(noumena.purpleDim("Restore failed"));
    p.log.error(`Error: ${err.message || err}`);
    return;
  }

  // Import to NPL
  s.start("Importing to NPL...");
  try {
    await bootstrapNpl();
    s.stop(noumena.success("NPL updated"));
  } catch (err: any) {
    s.stop(noumena.purpleDim("NPL import failed"));
    p.log.error(`Error: ${err.message || err}`);
  }

  // Reload Gateway
  s.start("Reloading Gateway...");
  try {
    await reloadGatewayConfig();
    s.stop(noumena.success("Gateway reloaded"));
    configDirty = false;
  } catch (err: any) {
    s.stop(noumena.purpleDim("Gateway reload failed"));
    p.log.error(`Error: ${err.message || err}`);
  }

  console.log();
  console.log(noumena.success("  ✓ Factory defaults restored!"));
  console.log(noumena.textDim("  Default users: jarvis, alice, bob"));
  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

/**
 * Import from a specific file (file picker)
 */
async function importFromFileFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Import from File"));
  console.log();

  const filePath = await p.text({
    message: "Enter path to YAML file:",
    placeholder: "/path/to/services.yaml or ./backup.yaml",
    validate: (v) => {
      if (!v || v.trim().length === 0) return "Path is required";
      return undefined;
    },
  });

  if (p.isCancel(filePath)) {
    return;
  }

  const fs = await import("fs/promises");
  const path = await import("path");

  // Resolve path (support relative paths)
  const resolvedPath = path.resolve(String(filePath).trim());

  // Check if file exists
  try {
    await fs.access(resolvedPath);
  } catch {
    p.log.error(`File not found: ${resolvedPath}`);
    return;
  }

  console.log();
  console.log(noumena.textDim(`  Source: ${resolvedPath}`));
  console.log(noumena.textDim(`  Target: ${getConfigPath()}`));
  console.log();
  console.log(noumena.warning("  ⚠  This will overwrite services.yaml and sync to NPL!"));
  console.log();

  const confirm = await p.confirm({
    message: "Proceed with import?",
    initialValue: false,
  });

  if (p.isCancel(confirm) || !confirm) {
    p.log.info("Import cancelled");
    return;
  }

  const s = p.spinner();
  s.start("Copying file...");

  try {
    // Read source file
    const content = await fs.readFile(resolvedPath, "utf-8");
    
    // Write to services.yaml
    await fs.writeFile(getConfigPath(), content, "utf-8");
    
    s.stop(noumena.success("File copied"));
  } catch (err: any) {
    s.stop(noumena.purpleDim("Copy failed"));
    p.log.error(`Error: ${err.message || err}`);
    return;
  }

  // Now import to NPL (skip confirmation since already confirmed above)
  p.log.info("Importing to NPL...");
  await importFromYamlFlow(true);
}

/**
 * Export to YAML with versioning
 */
async function exportToYamlFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");

  console.log();
  console.log(noumena.purple("  Export Configuration"));
  console.log();
  console.log(noumena.textDim("  Creates a timestamped backup of services.yaml"));
  console.log();

  const confirm = await p.confirm({
    message: "Create backup of current configuration?",
    initialValue: true,
  });

  if (p.isCancel(confirm) || !confirm) {
    p.log.info("Export cancelled");
    return;
  }

  const s = p.spinner();
  s.start("Creating backup...");

  try {
    const configPath = getConfigPath();
    const configDir = path.dirname(configPath);
    
    // Create timestamp
    const now = new Date();
    const timestamp = now.toISOString().replace(/:/g, "-").replace(/\..+/, "").replace("T", "_");
    const backupPath = path.join(configDir, `services.yaml.${timestamp}.backup`);
    
    // Read current config
    const content = await fs.readFile(configPath, "utf-8");
    
    // Write backup
    await fs.writeFile(backupPath, content, "utf-8");
    
    s.stop(noumena.success("Backup created"));
    p.log.info(`Saved to: ${path.basename(backupPath)}`);
  } catch (err: any) {
    s.stop(noumena.purpleDim("Backup failed"));
    p.log.error(`Error: ${err.message || err}`);
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

/**
 * View factory defaults
 */
async function viewFactoryDefaultsFlow(): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");

  console.log();
  console.log(noumena.purple("  Factory Defaults (services.yaml.default)"));
  console.log();

  try {
    const configPath = getConfigPath();
    const defaultPath = configPath.replace("services.yaml", "services.yaml.default");
    const content = await fs.readFile(defaultPath, "utf-8");
    
    console.log(noumena.textDim("  ─".repeat(40)));
    console.log(content);
    console.log(noumena.textDim("  ─".repeat(40)));
  } catch (err: any) {
    p.log.error(`Error reading factory defaults: ${err.message || err}`);
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

/**
 * Browse and restore from backups
 */
async function browseBackupsFlow(backupFiles: string[]): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");
  const configPath = getConfigPath();
  const configDir = path.dirname(configPath);

  while (true) {
    console.log();
    console.log(noumena.purple("  Backup Browser"));
    console.log(noumena.textDim(`  ${backupFiles.length} backup(s) available`));
    console.log();

    // Build options with file stats
    const options: { value: string; label: string; hint: string }[] = [
      { value: "back", label: noumena.textDim("← Back"), hint: "" },
    ];

    for (const file of backupFiles) {
      try {
        const filePath = path.join(configDir, file);
        const stats = await fs.stat(filePath);
        const size = `${Math.round(stats.size / 1024)}KB`;
        const date = stats.mtime.toLocaleString();
        
        options.push({
          value: file,
          label: `  ${file.replace("services.yaml.", "").replace(".backup", "")}`,
          hint: `${size} | ${date}`,
        });
      } catch {
        options.push({
          value: file,
          label: `  ${file}`,
          hint: "",
        });
      }
    }

    const selected = await p.select({
      message: "Select backup to view/restore:",
      options,
    });

    if (p.isCancel(selected) || selected === "back") {
      return;
    }

    await backupActionsFlow(selected);
  }
}

/**
 * Actions for a specific backup file
 */
async function backupActionsFlow(backupFile: string): Promise<void> {
  const fs = await import("fs/promises");
  const path = await import("path");
  const configPath = getConfigPath();
  const configDir = path.dirname(configPath);
  const backupPath = path.join(configDir, backupFile);

  console.log();
  console.log(noumena.purple(`  Backup: ${backupFile}`));
  console.log();

  const action = await p.select({
    message: "Action:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      { value: "view", label: "View contents", hint: "Display YAML contents" },
      { value: "restore", label: noumena.warning("Restore this backup"), hint: "Replace services.yaml and import to NPL" },
      { value: "delete", label: noumena.purpleDim("Delete backup"), hint: "Remove backup file" },
    ],
  });

  if (p.isCancel(action) || action === "back") {
    return;
  }

  if (action === "view") {
    console.log();
    console.log(noumena.textDim("  ─".repeat(40)));
    try {
      const content = await fs.readFile(backupPath, "utf-8");
      console.log(content);
    } catch (err: any) {
      p.log.error(`Error: ${err.message || err}`);
    }
    console.log(noumena.textDim("  ─".repeat(40)));
    console.log();
    await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
  } else if (action === "restore") {
    console.log();
    console.log(noumena.warning("  ⚠  This will overwrite services.yaml and sync to NPL!"));
    console.log();

    const confirm = await p.confirm({
      message: `Restore from ${backupFile}?`,
      initialValue: false,
    });

    if (p.isCancel(confirm) || !confirm) {
      p.log.info("Restore cancelled");
      return;
    }

    const s = p.spinner();
    s.start("Restoring backup...");

    try {
      const content = await fs.readFile(backupPath, "utf-8");
      await fs.writeFile(configPath, content, "utf-8");
      s.stop(noumena.success("Backup restored"));
      
      // Import to NPL (skip confirmation since already confirmed above)
      p.log.info("Importing to NPL...");
      await importFromYamlFlow(true);
    } catch (err: any) {
      s.stop(noumena.purpleDim("Restore failed"));
      p.log.error(`Error: ${err.message || err}`);
    }
  } else if (action === "delete") {
    const confirm = await p.confirm({
      message: `Delete ${backupFile}?`,
      initialValue: false,
    });

    if (p.isCancel(confirm) || !confirm) {
      return;
    }

    try {
      await fs.unlink(backupPath);
      p.log.success("Backup deleted");
    } catch (err: any) {
      p.log.error(`Error: ${err.message || err}`);
    }
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
 * Check if a container for this service is running
 */
function isContainerRunning(imageName: string): boolean {
  try {
    const result = execSync(`docker ps --filter ancestor=${imageName} --format "{{.ID}}"`, { 
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "ignore"] 
    });
    return result.trim().length > 0;
  } catch {
    return false;
  }
}

/**
 * Get container ID for a running container with this image
 */
function getContainerId(imageName: string): string | null {
  try {
    const result = execSync(`docker ps --filter ancestor=${imageName} --format "{{.ID}}"`, { 
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "ignore"] 
    });
    const id = result.trim().split("\n")[0];
    return id || null;
  } catch {
    return null;
  }
}

/**
 * Start a container for this service
 */
function startContainer(service: ServiceDefinition): { success: boolean; error?: string; command?: string } {
  try {
    const imageName = getDockerImageName(service);
    if (!imageName) return { success: false, error: "Not a Docker service" };
    
    // Start container in detached mode with a name
    const containerName = `mcp-${service.name}`;
    
    // First check if container with this name already exists
    try {
      const existing = execSync(`docker ps -a --filter name=^${containerName}$ --format "{{.ID}}"`, { 
        encoding: "utf-8",
        stdio: ["pipe", "pipe", "ignore"]
      });
      
      if (existing.trim()) {
        // Container exists - remove it first
        execSync(`docker rm -f ${containerName}`, { stdio: "pipe" });
      }
    } catch (e) {
      // Ignore - container doesn't exist or cleanup failed
    }
    
    // Create and start new container in detached mode
    const runCmd = `docker run -d --name ${containerName} ${imageName}`;
    try {
      const result = execSync(runCmd, { encoding: "utf-8", stdio: "pipe" });
      return { success: true, command: runCmd };
    } catch (err: any) {
      let errorMsg = "Unknown error";
      
      if (err.stderr) {
        errorMsg = err.stderr.toString().trim();
      } else if (err.stdout) {
        errorMsg = err.stdout.toString().trim();
      } else if (err.message) {
        errorMsg = err.message;
      }
      
      return { success: false, error: errorMsg, command: runCmd };
    }
  } catch (err: any) {
    let errorMsg = "Unknown error";
    
    if (err.stderr) {
      errorMsg = err.stderr.toString().trim();
    } else if (err.stdout) {
      errorMsg = err.stdout.toString().trim();
    } else if (err.message) {
      errorMsg = err.message;
    }
    
    return { success: false, error: errorMsg };
  }
}

/**
 * Stop a running container
 */
function stopContainer(containerId: string): boolean {
  try {
    execSync(`docker stop ${containerId}`, { stdio: "pipe" });
    execSync(`docker rm ${containerId}`, { stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
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
 * Show main menu and handle selection.
 *
 * Organized into three sections:
 *   1. Gateway Settings — services list + add new services
 *   2. User Management — per-user/agent tool access
 *   3. System — NPL bootstrap, reload, quit
 */
async function mainMenu(): Promise<boolean> {
  const config = loadConfig();
  const services = config.services;
  const gatewayConnected = await checkGatewayHealth();
  const nplReady = await isNplBootstrapped();
  
  // Build status line
  const gatewayStatus = gatewayConnected 
    ? noumena.success("● Connected") 
    : noumena.grayDim("○ Disconnected");
  const enabledCount = services.filter(s => s.enabled).length;
  const userCount = getAllUsers().length;
  
  console.clear();
  showHeader();
  
  // Compact status bar
  console.log(
    noumena.textDim("  Gateway: ") + gatewayStatus + 
    noumena.textDim("  |  Services: ") + noumena.text(`${enabledCount}/${services.length}`) +
    noumena.textDim("  |  Users: ") + noumena.text(`${userCount}`)
  );
  console.log(
    noumena.textDim("  ") +
    noumena.success("●") + noumena.textDim(" enabled  ") +
    noumena.grayDim("–") + noumena.textDim(" disabled  ") +
    noumena.success("■") + noumena.textDim(" image ready  ") +
    noumena.grayDim("·") + noumena.textDim(" not pulled")
  );
  console.log();

  // ── Build service options with status indicators ──
  const serviceOptions: { value: string; label: string; hint?: string }[] = services.map(service => {
    const statusIcon = service.enabled ? noumena.success("●") : noumena.grayDim("–");
    const imageName = getDockerImageName(service);
    let containerIcon = "";
    if (imageName) {
      const pulled = imageExists(imageName);
      if (service.type !== "MCP_STDIO") {
        const running = isContainerRunning(imageName);
        if (running) {
          containerIcon = noumena.success(" ▶");
        } else if (pulled) {
          containerIcon = noumena.success(" ■");
        } else {
          containerIcon = noumena.grayDim(" ·");
        }
      } else {
        containerIcon = pulled ? noumena.success(" ■") : noumena.grayDim(" ·");
      }
    }
    const enabledTools = service.tools.filter(t => t.enabled !== false).length;
    
    return {
      value: `service:${service.name}`,
      label: `${statusIcon}${containerIcon}  ${service.displayName}`,
      hint: `${getTypeLabel(service.type)} | ${enabledTools}/${service.tools.length} tools`,
    };
  });

  if (services.length === 0) {
    serviceOptions.push({
      value: "---no-services",
      label: noumena.textDim("  No services configured yet"),
      hint: "",
    });
  }

  // ── Assemble menu with section headers ──
  const options: { value: string; label: string; hint?: string }[] = [
    // Gateway Settings section
    { value: "---hdr-gw", label: noumena.purple("── Gateway Settings ──"), hint: "" },
    ...serviceOptions,
    { value: "search", label: noumena.accent("  + Search Docker Hub"), hint: "Find and add MCP servers" },
    { value: "custom", label: noumena.accent("  + Add MCP service"), hint: "Templates, NPM, or Docker" },

    // User Management section
    { value: "---hdr-users", label: noumena.purple("── User Management ──"), hint: "" },
    { value: "users", label: `  Manage users & tool access`, hint: `${userCount} user(s)` },

    // System section
    { value: "---hdr-sys", label: noumena.purple("── System ──"), hint: "" },
    { value: "credentials", label: "  Manage credentials", hint: "Vault & credential mapping" },
    { value: "config", label: "  Configuration Manager", hint: "Manage services.yaml, Import/Export, Backups" },
    { value: "gateway", label: `  Reload Gateway  ${configDirty ? noumena.warning("⚠") : noumena.success("✓")}`, hint: configDirty ? "Manual reload needed" : "Auto-reloads after imports" },
    { value: "quit", label: "  Quit", hint: "" },
  ];

  const action = await p.select({
    message: "Select:",
    options,
  });

  if (p.isCancel(action) || action === "quit") {
    return false;
  }

  // Ignore section headers and empty placeholders
  if (typeof action === "string" && (action.startsWith("---"))) {
    return true;
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
  } else if (action === "users") {
    await userManagementFlow();
  } else if (action === "credentials") {
    await credentialManagementFlow();
  } else if (action === "config") {
    await configurationManagerFlow();
  } else if (action === "gateway") {
    await reloadGatewayFlow();
  }

  // Mark config as potentially dirty after config-modifying flows
  if (typeof action === "string" && !["config", "gateway", "quit"].includes(action) && !action.startsWith("---")) {
    configDirty = true;
  }

  return true;
}

/**
 * Service actions flow - shows actions for a selected service
 */
async function serviceActionsFlow(service: ServiceDefinition): Promise<void> {
  const imageName = getDockerImageName(service);
  const isDocker = imageName !== null;
  const hasImage = isDocker ? imageExists(imageName) : false;
  const containerRunning = isDocker && hasImage ? isContainerRunning(imageName) : false;
  
  // Show service info header
  console.log();
  console.log(noumena.purple(`  ${service.displayName}`));
  console.log(noumena.textDim(`  Type: ${getTypeLabel(service.type)}`));
  console.log(noumena.textDim(`  Status: `) + (service.enabled ? noumena.success("Enabled") : noumena.grayDim("Disabled")));
  if (isDocker) {
    console.log(noumena.textDim(`  Image: ${imageName}`) + (hasImage ? noumena.success(" (pulled)") : noumena.grayDim(" (not pulled)")));
    if (service.type === "MCP_STDIO") {
      console.log(noumena.textDim(`  Transport: `) + noumena.text("STDIN/STDOUT (ephemeral containers)"));
      console.log(noumena.textDim(`  Note: `) + noumena.textDim("Gateway spawns containers on-demand when tools are called"));
    } else if (service.type === "MCP_HTTP") {
      if (hasImage && containerRunning) {
        console.log(noumena.textDim(`  Container: `) + noumena.success("Running"));
      } else if (hasImage) {
        console.log(noumena.textDim(`  Container: `) + noumena.grayDim("Stopped"));
      }
    }
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
  
  // Container start/stop (only for non-STDIO services with image available)
  // STDIO services can't stay running in detached mode - they need stdin/stdout
  if (isDocker && hasImage && service.type !== "MCP_STDIO") {
    if (containerRunning) {
      options.push({ value: "stop", label: "Stop container", hint: "Stop the running Docker container" });
    } else {
      options.push({ value: "start", label: "Start container", hint: "Start a Docker container" });
    }
  }
  
  // Image pull (only if image not available)
  if (isDocker && !hasImage) {
    options.push({ value: "pull", label: "Pull image", hint: `docker pull ${imageName}` });
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
  } else if (action === "start") {
    // Note: STDIO services may exit immediately when started in detached mode
    // as they expect stdin/stdout communication. Gateway spawns them on-demand.
    console.log();
    if (service.type === "MCP_STDIO") {
      p.log.info("Note: STDIO services expect stdin/stdout and may exit immediately.");
      p.log.info("The Gateway will spawn containers on-demand when tools are called.");
      console.log();
      
      const shouldContinue = await p.confirm({
        message: "Start container anyway for testing?",
        initialValue: false,
      });
      
      if (p.isCancel(shouldContinue) || !shouldContinue) {
        return;
      }
    }
    
    s.start("Starting container...");
    const result = startContainer(service);
    if (result.success) {
      s.stop(noumena.success("Container created"));
      
      if (result.command) {
        p.log.info(noumena.textDim(`Command: ${result.command}`));
      }
      
      // Give it a moment to start, then check status
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      if (imageName && isContainerRunning(imageName)) {
        p.log.success("Container is running");
      } else {
        p.log.warn("Container exited (expected for STDIO services without stdin/stdout connection)");
        // Show container logs to help diagnose issues
        if (imageName) {
          try {
            const containerName = `mcp-${service.name}`;
            const logs = execSync(`docker logs ${containerName} 2>&1 | tail -10`, { 
              encoding: "utf-8",
              stdio: ["pipe", "pipe", "ignore"]
            });
            if (logs.trim()) {
              console.log(noumena.textDim("  Last container output:"));
              logs.trim().split("\n").forEach(line => {
                console.log(noumena.textDim(`    ${line}`));
              });
            }
          } catch {
            // Ignore log fetch errors
          }
        }
      }
    } else {
      s.stop(noumena.purpleDim("Failed to start container"));
      if (result.command) {
        console.log();
        console.log(noumena.textDim("  Command: ") + result.command);
      }
      if (result.error) {
        console.log();
        console.log(noumena.textDim("  Error:"));
        result.error.split("\n").forEach(line => {
          console.log(noumena.textDim(`    ${line}`));
        });
      }
    }
  } else if (action === "stop" && imageName) {
    const containerId = getContainerId(imageName);
    if (containerId) {
      s.start("Stopping container...");
      const success = stopContainer(containerId);
      if (success) {
        s.stop(noumena.success("Container stopped"));
      } else {
        s.stop(noumena.purpleDim("Failed to stop container"));
      }
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

      // Step 1: Disable in NPL (remove from allowed services)
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
      try {
        await reloadGatewayConfig();
        p.log.success("All tools enabled (Gateway reloaded)");
      } catch {
        p.log.success("All tools enabled");
        p.log.warn("Gateway reload failed — restart Gateway to apply");
      }
      continue;
    }

    if (action === "disable_all") {
      for (const tool of freshService.tools) {
        setToolEnabled(freshService.name, tool.name, false);
      }
      try {
        await reloadGatewayConfig();
        p.log.success("All tools disabled (Gateway reloaded)");
      } catch {
        p.log.success("All tools disabled");
        p.log.warn("Gateway reload failed — restart Gateway to apply");
      }
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
    
    try {
      await reloadGatewayConfig();
      p.log.success(`${selectedTools.length} tool(s) ${newEnabled ? "enabled" : "disabled"} (Gateway reloaded)`);
    } catch {
      p.log.success(`${selectedTools.length} tool(s) ${newEnabled ? "enabled" : "disabled"}`);
      p.log.warn("Gateway reload failed — restart Gateway to apply");
    }
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
  console.log(noumena.textDim(`  Namespaced as: ${service.name}.{tool}`));
  
  for (const tool of service.tools) {
    const enabled = tool.enabled !== false;
    const status = enabled ? noumena.success("✓") : noumena.grayDim("–");
    const namespacedName = `${service.name}.${tool.name}`;
    console.log(`    ${status} ${namespacedName}`);
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

// Service templates for common MCP servers
const SERVICE_TEMPLATES: Record<string, {
  displayName: string;
  description: string;
  command: string;
  args: string[];
  requiresCredentials: boolean;
  credentialName?: string;
  setupGuide: string;
}> = {
  gemini: {
    displayName: "Google Gemini",
    description: "Google Gemini AI API for text generation and chat",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-google-gemini"],
    requiresCredentials: true,
    credentialName: "gemini",
    setupGuide: "You'll need a Gemini API key from https://aistudio.google.com/apikey",
  },
  github: {
    displayName: "GitHub",
    description: "GitHub API for repository management and code operations",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-github"],
    requiresCredentials: true,
    credentialName: "github",
    setupGuide: "You'll need a GitHub Personal Access Token from https://github.com/settings/tokens",
  },
  filesystem: {
    displayName: "Filesystem",
    description: "Local filesystem access for reading and writing files",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-filesystem"],
    requiresCredentials: false,
    setupGuide: "Provides safe filesystem operations within configured directories",
  },
  memory: {
    displayName: "Memory",
    description: "Persistent memory/knowledge graph storage",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-memory"],
    requiresCredentials: false,
    setupGuide: "Stores and retrieves information across sessions",
  },
  brave_search: {
    displayName: "Brave Search",
    description: "Web search via Brave Search API",
    command: "npx",
    args: ["-y", "@modelcontextprotocol/server-brave-search"],
    requiresCredentials: true,
    credentialName: "brave_search",
    setupGuide: "You'll need a Brave Search API key from https://brave.com/search/api/",
  },
};

/**
 * Add a custom Docker image (local or private registry)
 */
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

  if (p.isCancel(serviceType) || serviceType === "back") {
    return;
  }

  if (serviceType === "template") {
    await addTemplateServiceFlow();
  } else if (serviceType === "npm") {
    await addNpmServiceFlow();
  } else if (serviceType === "docker") {
    await addDockerServiceFlow();
  }
}

/**
 * Add a service from a template
 */
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
        hint: template.requiresCredentials ? "🔐 requires credentials" : "no credentials needed",
      })),
    ],
  });

  if (p.isCancel(templateName) || templateName === "back") {
    return;
  }

  const template = SERVICE_TEMPLATES[String(templateName)];
  
  console.log();
  console.log(noumena.success(`  ✓ Selected: ${template.displayName}`));
  console.log(noumena.textDim(`  ${template.description}`));
  console.log();
  console.log(noumena.textDim(`  💡 ${template.setupGuide}`));
  console.log();

  // Check if service already exists
  const config = loadConfig();
  if (config.services.some(s => s.name === templateName)) {
    p.log.warn(`Service '${templateName}' already exists`);
    return;
  }

  // Confirm
  const confirmed = await p.confirm({
    message: `Add ${template.displayName}?`,
    initialValue: true,
  });

  if (p.isCancel(confirmed) || !confirmed) {
    return;
  }

  // Create service definition
  const newService: ServiceDefinition = {
    name: String(templateName),
    displayName: template.displayName,
    type: "MCP_STDIO",
    enabled: true,  // Enable by default for templates
    command: template.command,
    args: template.args,
    requiresCredentials: template.requiresCredentials,
    description: template.description,
    tools: [],
  };

  const success = addService(newService);
  if (!success) {
    p.log.warn("Failed to add service");
    return;
  }

  p.log.success(`✓ Added: ${template.displayName}`);

  // Add placeholder tool
  const { addTool } = await import("./lib/config.js");
  addTool(String(templateName), {
    name: `${templateName}_default`,
    description: `Default tool for ${templateName} - run 'Discover tools' to find actual tools`,
    inputSchema: { type: "object", properties: {}, required: [] },
    enabled: true,
  });

  try {
    await reloadGatewayConfig();
  } catch {
    // Ignore
  }

  // If requires credentials, prompt to set them up now
  if (template.requiresCredentials) {
    console.log();
    console.log(noumena.warning("  ⚠️  This service requires credentials"));
    console.log();

    const setupCreds = await p.confirm({
      message: "Set up credentials now?",
      initialValue: true,
    });

    if (!p.isCancel(setupCreds) && setupCreds) {
      // Check if credential already exists
      const credConfig = loadCredentialsConfig();
      const credName = template.credentialName || String(templateName);
      
      if (!credConfig.credentials[credName]) {
        console.log();
        p.log.info(`Creating credential: ${credName}`);
        await addCredentialFlow();
      } else {
        p.log.success(`Credential '${credName}' already exists`);
      }

      // Configure service to use the credential
      console.log();
      const linkCred = await p.confirm({
        message: `Link ${template.displayName} to '${credName}' credential?`,
        initialValue: true,
      });

      if (!p.isCancel(linkCred) && linkCred) {
        setServiceCredential(String(templateName), credName);
        p.log.success(`✓ ${template.displayName} will use '${credName}' credentials`);
      }
    }
  }

  console.log();
  p.log.info("✨ Service added! Don't forget to grant user access via 'User Management'");
}

/**
 * Add an NPM-based MCP server
 */
async function addNpmServiceFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add NPM-based MCP Service"));
  console.log(noumena.textDim("  For services installed via 'npx' or 'npm'"));
  console.log();
  console.log(noumena.textDim("  Examples:"));
  console.log(noumena.textDim("    • @modelcontextprotocol/server-github"));
  console.log(noumena.textDim("    • @modelcontextprotocol/server-google-gemini"));
  console.log(noumena.textDim("    • my-custom-mcp-server"));
  console.log();

  const packageName = await p.text({
    message: "NPM package name:",
    placeholder: "e.g., @modelcontextprotocol/server-github",
    validate: (value) => {
      if (!value || value.trim().length === 0) {
        return "Package name is required";
      }
      return undefined;
    },
  });

  if (p.isCancel(packageName)) {
    return;
  }

  const packageNameStr = String(packageName).trim();

  // Suggest service name from package
  const extractServiceName = (pkg: string): string => {
    const parts = pkg.split("/");
    const last = parts[parts.length - 1];
    return last.replace("server-", "").replace(/@/g, "");
  };

  const suggestedName = extractServiceName(packageNameStr);

  const serviceName = await p.text({
    message: "Service name:",
    initialValue: suggestedName,
    placeholder: "e.g., github, gemini",
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

  const serviceNameStr = String(serviceName).trim();

  const displayName = await p.text({
    message: "Display name:",
    initialValue: serviceNameStr.split(/[-_]/).map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" "),
    placeholder: "Human-readable name for the service",
  });

  if (p.isCancel(displayName)) {
    return;
  }

  const description = await p.text({
    message: "Description:",
    placeholder: "Brief description of what this MCP service does",
    initialValue: `MCP service: ${displayName}`,
  });

  if (p.isCancel(description)) {
    return;
  }

  const requiresCreds = await p.confirm({
    message: "Does this service require credentials?",
    initialValue: true,
  });

  if (p.isCancel(requiresCreds)) {
    return;
  }

  // Create service definition
  const newService: ServiceDefinition = {
    name: serviceNameStr,
    displayName: String(displayName).trim(),
    type: "MCP_STDIO",
    enabled: true,  // Enable by default
    command: "npx",
    args: ["-y", packageNameStr],
    requiresCredentials: Boolean(requiresCreds),
    description: String(description).trim(),
    tools: [],
  };

  const success = addService(newService);
  if (!success) {
    p.log.warn("Failed to add service");
    return;
  }

  p.log.success(`✓ Added: ${displayName}`);

  // Add placeholder tool
  const { addTool } = await import("./lib/config.js");
  addTool(serviceNameStr, {
    name: `${serviceNameStr}_default`,
    description: `Default tool for ${serviceNameStr} - run 'Discover tools' to find actual tools`,
    inputSchema: { type: "object", properties: {}, required: [] },
    enabled: true,
  });

  try {
    await reloadGatewayConfig();
  } catch {
    // Ignore
  }

  if (requiresCreds) {
    console.log();
    console.log(noumena.warning("  ⚠️  Don't forget to set up credentials!"));
    console.log(noumena.textDim("     Go to: System > Manage credentials"));
  }

  console.log();
  p.log.info("✨ Service added! Don't forget to grant user access via 'User Management'");
}

/**
 * Add a Docker-based MCP service
 */
async function addDockerServiceFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add Docker-based MCP Service"));
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
      console.log();
      console.log(noumena.purple(`  Pulling ${imageNameStr}...`));
      console.log();
      try {
        execSync(`docker pull ${imageNameStr}`, { stdio: "inherit" });
        console.log();
        p.log.success("Image pulled successfully");
      } catch (err) {
        console.log();
        p.log.error("Failed to pull image");
        
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
 * View the current services.yaml configuration
 */
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

/**
 * Open services.yaml in the user's preferred editor
 */
async function editConfigFlow(): Promise<void> {
  const configPath = getConfigPath();
  const editor = process.env.EDITOR || process.env.VISUAL || "nano";
  
  p.log.info(`Opening ${configPath} in ${editor}...`);
  
  try {
    spawnSync(editor, [configPath], { stdio: "inherit" });
    configDirty = true;
    p.log.success("Editor closed. Use Reload Gateway to apply changes.");
  } catch (error) {
    p.log.error(`Failed to open editor: ${error}`);
  }
}

/**
 * Reload Gateway configuration (manual fallback)
 * 
 * Gateway automatically reloads after:
 * - Import from YAML
 * - Edit and Apply
 * - Reset to Factory Defaults
 * 
 * Use this only when:
 * - Auto-reload failed
 * - Manual edits made outside TUI
 * - Recovery after Gateway restart
 */
async function reloadGatewayFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Reload Gateway"));
  console.log();
  console.log(noumena.textDim("  Manual reload for edge cases:"));
  console.log(noumena.textDim("  • Auto-reload failed after import"));
  console.log(noumena.textDim("  • Manual YAML edits outside TUI"));
  console.log(noumena.textDim("  • Recovery after Gateway restart"));
  console.log();
  console.log(noumena.textDim("  Note: Gateway auto-reloads after Import/Edit operations"));
  console.log();

  const s = p.spinner();
  s.start("Reloading Gateway configuration...");

  try {
    await reloadGatewayConfig();
    s.stop(noumena.success("Gateway configuration reloaded"));
    configDirty = false;
    
    console.log();
    p.log.success("✓ Gateway now has fresh NPL cache and services.yaml");
  } catch (err: any) {
    s.stop(noumena.purpleDim("Failed to reload Gateway config"));
    p.log.error(`Error: ${err.message || err}`);
    p.log.info("Check that Gateway is running and try again");
  }

  console.log();
  await p.text({ message: "Press Enter to continue...", defaultValue: "", placeholder: "" });
}

// ============================================================================
// User Management Flows
// ============================================================================

/**
 * System/service accounts that should be hidden from user management.
 * These are infrastructure accounts, not end users.
 */
const SYSTEM_USERNAMES = new Set(["admin", "service-account-mcpgateway"]);

/**
 * Filter out system/service accounts from a Keycloak user list.
 */
function filterSystemUsers(users: KeycloakUser[]): KeycloakUser[] {
  return users.filter(u => !SYSTEM_USERNAMES.has(u.username));
}

/**
 * User management main menu (V3 Architecture).
 * 
 * Keycloak is the source of truth for identity (who exists).
 * NPL tracks who is "registered for the Gateway" (authorization).
 * services.yaml is a configuration export, not the source of truth.
 * 
 * Flow:
 * 1. Query Keycloak for all users (identity source)
 * 2. Check NPL registration status (authorization)
 * 3. Show registration actions (register/deregister from Gateway)
 * 4. Show identity actions (create/delete in Keycloak)
 */
async function userManagementFlow(): Promise<void> {
  while (true) {
    // 1. Load all Keycloak users (source of truth for identity)
    let keycloakUsers: KeycloakUser[] = [];
    let keycloakError = "";
    try {
      keycloakUsers = await listKeycloakUsers();
    } catch (err) {
      keycloakError = `${err}`;
    }

    // Filter out system accounts (admin and gateway service)
    // These should not be registered as regular Gateway users
    const isSystemAccount = (user: KeycloakUser): boolean => {
      const username = user.username?.toLowerCase() || "";
      const email = user.email?.toLowerCase() || "";
      const roles = user.attributes?.role || [];
      
      // Filter by username
      if (username === "admin" || username === "agent") return true;
      
      // Filter by email
      if (email === "admin@acme.com" || email === "agent@acme.com") return true;
      
      // Filter by role (admin or gateway system role)
      if (roles.includes("admin") || roles.includes("gateway")) return true;
      
      return false;
    };
    
    const regularUsers = keycloakUsers.filter(u => !isSystemAccount(u));
    const systemUsers = keycloakUsers.filter(u => isSystemAccount(u));

    // 2. Load services.yaml users (for tool access configuration)
    const localUsers = getAllUsers();
    const localUsersByEmail = new Map(localUsers.map(u => [u.userId, u]));

    // 3. Check NPL registration status for each Keycloak user
    const nplRegistered = new Set<string>();
    try {
      const token = await getKeycloakToken();
      for (const kcUser of regularUsers) {
        if (!kcUser.email) continue;
        const accessId = await findUserToolAccess(token, kcUser.email);
        if (accessId) {
          nplRegistered.add(kcUser.email);
        }
      }
    } catch (err) {
      // NPL check failed, continue without NPL status
    }

    console.clear();
    console.log();
    console.log(noumena.purple("  User Management"));
    console.log();
    console.log(noumena.textDim("  💡 Keycloak: Identity (who you are) | Gateway: Authorization (what you can do)"));
    console.log(noumena.textDim("  • Users in Keycloak can be registered for Gateway access (→ NPL + services.yaml)"));
    console.log();
    console.log(noumena.textDim(`  ${regularUsers.length} user(s) in Keycloak (${systemUsers.length} system accounts hidden)`));
    if (keycloakError) {
      console.log(noumena.warning(`  ⚠ Keycloak error: ${keycloakError.substring(0, 60)}`));
    }
    console.log();

    // Build user list from Keycloak with Gateway registration status
    const userOptions: { value: string; label: string; hint?: string }[] = regularUsers.map(kcUser => {
      const email = kcUser.email || "(no email)";
      const role = kcUser.attributes?.role?.[0] || "user";
      const localUser = localUsersByEmail.get(email);
      
      // Registration status
      const isRegisteredInNpl = nplRegistered.has(email);
      const isInConfig = !!localUser;
      const registrationIcon = (isRegisteredInNpl && isInConfig) ? noumena.success("✓") : noumena.grayDim("○");
      const registrationStatus = (isRegisteredInNpl && isInConfig) 
        ? noumena.success("Registered")
        : noumena.grayDim("Not registered");
      
      // Tool access count
      const serviceCount = localUser ? Object.keys(localUser.tools).length : 0;
      const totalTools = localUser ? Object.values(localUser.tools).reduce((sum, t) => sum + t.length, 0) : 0;
      const toolInfo = totalTools > 0 ? `${totalTools} tools` : "no tools yet";

      return {
        value: `user:${kcUser.id}`,
        label: `${registrationIcon}  ${kcUser.firstName || ""} ${kcUser.lastName || email} ${noumena.textDim(`(${role})`)}`,
        hint: `${email} | ${registrationStatus} | ${toolInfo}`,
      };
    });

    const allOptions: { value: string; label: string; hint?: string }[] = [
      { value: "back", label: noumena.textDim("← Back") },
      ...userOptions,
      { value: "separator", label: noumena.textDim("───────────────────"), hint: "" },
      { value: "create", label: noumena.accent("+ Create new Keycloak user"), hint: "Add user to identity system" },
    ];

    const action = await p.select({
      message: "Select a user or action:",
      options: allOptions.filter(o => o.value !== "separator"),
    });

    if (p.isCancel(action) || action === "back") {
      break;
    }

    if (typeof action === "string" && action.startsWith("user:")) {
      const keycloakId = action.replace("user:", "");
      const kcUser = keycloakUsers.find(u => u.id === keycloakId);
      if (!kcUser || !kcUser.email) continue;

      await userActionsFlowV3(kcUser, localUsersByEmail.get(kcUser.email));
    } else if (action === "create") {
      await createUserFlow();
    }
  }
}

/**
 * Create a new user in Keycloak and NPL
 */
async function createUserFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Create New User"));
  console.log();

  const email = await p.text({
    message: "Email:",
    initialValue: "newuser@acme.com",
    validate: (v) => {
      if (!v || !v.includes("@") || !v.includes(".")) return "Valid email required";
      const users = getAllUsers();
      if (users.some(u => u.userId === v)) return "User already exists";
      return undefined;
    },
  });

  if (p.isCancel(email)) return;

  const emailStr = String(email).trim();

  const username = await p.text({
    message: "Username:",
    initialValue: emailStr.split("@")[0],
  });

  if (p.isCancel(username)) return;

  const firstName = await p.text({
    message: "First name:",
    placeholder: "Optional",
  });

  if (p.isCancel(firstName)) return;

  const lastName = await p.text({
    message: "Last name:",
    placeholder: "Optional",
  });

  if (p.isCancel(lastName)) return;

  const password = await p.password({
    message: "Initial password:",
    validate: (v) => (!v || v.length < 8 ? "Password must be at least 8 characters" : undefined),
  });

  if (p.isCancel(password)) return;

  const usernameStr = String(username).trim();
  const firstNameStr = firstName ? String(firstName).trim() : "";
  const lastNameStr = lastName ? String(lastName).trim() : "";

  const s = p.spinner();
  
  try {
    // Step 1: Create in Keycloak
    s.start("Creating user in Keycloak...");
    const keycloakId = await createKeycloakUser(
      emailStr,
      usernameStr,
      firstNameStr || undefined,
      lastNameStr || undefined,
      String(password)
    );
    s.stop(noumena.success("User created in Keycloak"));

    // Step 2: Register in NPL
    s.start("Registering in NPL...");
    try {
      await registerUserInNpl(emailStr);
      s.stop(noumena.success("User registered in NPL"));
    } catch {
      s.stop(noumena.textDim("NPL registration skipped (NPL may not be available)"));
    }

    // Step 3: Add to services.yaml
    s.start("Saving to configuration...");
    const newUser: UserToolAccess = {
      userId: emailStr,
      keycloakId,
      displayName: `${firstNameStr} ${lastNameStr}`.trim() || usernameStr,
      createdAt: new Date().toISOString(),
      tools: {},
      vaultPaths: {},
    };
    
    const success = addUser(newUser);
    if (success) {
      s.stop(noumena.success("Configuration updated"));
      p.log.success(`User created: ${email}`);
      p.log.info("Use 'Edit tool access' to grant tools to this user");
    } else {
      s.stop(noumena.purpleDim("Failed to update configuration"));
    }
  } catch (error) {
    s.stop(noumena.purpleDim("Failed"));
    p.log.error(`${error}`);
  }
}

/**
 * User actions menu (V3 Architecture).
 * Keycloak user is the source, localUser may or may not exist.
 */
async function userActionsFlowV3(kcUser: KeycloakUser, localUser: UserToolAccess | undefined): Promise<void> {
  const email = kcUser.email || "(no email)";
  const role = kcUser.attributes?.role?.[0] || "user";
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || email;
  
  console.log();
  console.log(noumena.purple(`  ${displayName}`));
  console.log(noumena.textDim(`  Email: ${email}`));
  console.log(noumena.textDim(`  Role: ${role}`));
  console.log(noumena.textDim(`  Keycloak ID: ${kcUser.id}`));
  console.log();
  
  // Check registration status
  const isRegistered = !!localUser;
  let isInNpl = false;
  try {
    const token = await getKeycloakToken();
    const accessId = await findUserToolAccess(token, email);
    isInNpl = !!accessId;
  } catch {
    // NPL check failed
  }
  
  if (isRegistered && isInNpl) {
    const serviceCount = Object.keys(localUser.tools).length;
    const toolCount = Object.values(localUser.tools).reduce((sum, tools) => sum + tools.length, 0);
    console.log(noumena.success(`  ✓ Registered for Gateway`));
    console.log(noumena.textDim(`    Access: ${serviceCount} services, ${toolCount} tools`));
  } else if (isInNpl && !isRegistered) {
    console.log(noumena.warning(`  ⚠ Registered in NPL but not in services.yaml (inconsistent state)`));
  } else if (isRegistered && !isInNpl) {
    console.log(noumena.warning(`  ⚠ In services.yaml but not in NPL (inconsistent state)`));
  } else {
    console.log(noumena.grayDim(`  ○ Not registered for Gateway`));
  }
  console.log();

  // Build action options based on registration status
  const actionOptions: Array<{ value: string; label: string; hint?: string }> = [
    { value: "back", label: noumena.textDim("← Back") },
  ];
  
  if (isRegistered) {
    actionOptions.push(
      { value: "tools", label: "Edit tool access", hint: "Grant/revoke tools" },
      { value: "view", label: "View all access", hint: "Show detailed permissions" },
      { value: "deregister", label: noumena.warning("Deregister from Gateway"), hint: "Remove from NPL + services.yaml" }
    );
  } else {
    actionOptions.push(
      { value: "register", label: noumena.success("Register for Gateway"), hint: "Add to NPL + services.yaml" }
    );
  }
  
  actionOptions.push(
    { value: "separator", label: noumena.textDim("───────────────"), hint: "" },
    { value: "delete", label: noumena.purpleDim("Delete from Keycloak"), hint: "Remove identity (+ cascade to NPL)" }
  );

  const action = await p.select({
    message: "Action:",
    options: actionOptions.filter(o => o.value !== "separator"),
  });

  if (p.isCancel(action) || action === "back") {
    return;
  }

  if (action === "register") {
    await registerUserForGatewayFlow(kcUser);
  } else if (action === "deregister") {
    if (localUser) {
      await deregisterUserFromGatewayFlow(kcUser, localUser);
    }
  } else if (action === "tools") {
    if (localUser) {
      await editUserToolAccessFlow(localUser);
    }
  } else if (action === "view") {
    if (localUser) {
      await viewUserAccessFlow(localUser);
    }
  } else if (action === "delete") {
    await deleteUserFromKeycloakFlow(kcUser, localUser);
  }
}

/**
 * Register a Keycloak user for Gateway access (add to NPL + services.yaml)
 */
async function registerUserForGatewayFlow(kcUser: KeycloakUser): Promise<void> {
  const email = kcUser.email || "";
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || email;
  
  const confirmed = await p.confirm({
    message: `Register ${noumena.purple(displayName)} for Gateway access?`,
    initialValue: true,
  });

  if (p.isCancel(confirmed) || !confirmed) {
    return;
  }

  const s = p.spinner();

  try {
    // Step 1: Register in NPL
    s.start("Registering in NPL...");
    await registerUserInNpl(email);
    s.stop(noumena.success("Registered in NPL"));

    // Step 2: Add to services.yaml
    s.start("Saving to configuration...");
    const newUser: UserToolAccess = {
      userId: email,
      keycloakId: kcUser.id,
      displayName,
      createdAt: new Date().toISOString(),
      tools: {},
      vaultPaths: {},
    };
    
    const success = addUser(newUser);
    if (success) {
      s.stop(noumena.success("Configuration updated"));
      p.log.success(`User registered: ${email}`);
      p.log.info("Use 'Edit tool access' to grant tools to this user");
    } else {
      s.stop(noumena.purpleDim("Failed to update configuration"));
    }
  } catch (error) {
    s.stop(noumena.purpleDim("Failed"));
    p.log.error(`${error}`);
  }
}

/**
 * Deregister a user from Gateway (remove from NPL + services.yaml, keep in Keycloak)
 */
async function deregisterUserFromGatewayFlow(kcUser: KeycloakUser, localUser: UserToolAccess): Promise<void> {
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || localUser.userId;
  
  const confirmed = await p.confirm({
    message: `Deregister ${noumena.purple(displayName)} from Gateway? (Keycloak account will remain)`,
    initialValue: false,
  });

  if (p.isCancel(confirmed) || !confirmed) {
    return;
  }

  const s = p.spinner();

  try {
    // Step 1: Remove from NPL
    s.start("Removing from NPL...");
    try {
      await removeUserFromNpl(localUser.userId);
      s.stop(noumena.success("Removed from NPL"));
    } catch {
      s.stop(noumena.textDim("NPL removal skipped (not found)"));
    }

    // Step 2: Remove from services.yaml
    s.start("Removing from configuration...");
    const success = removeUserFromConfig(localUser.userId);
    if (success) {
      s.stop(noumena.success("Removed from configuration"));
      p.log.success(`User deregistered: ${localUser.userId}`);
      p.log.info("User can still authenticate via Keycloak but has no Gateway access");
    } else {
      s.stop(noumena.purpleDim("Failed to update configuration"));
    }
  } catch (error) {
    s.stop(noumena.purpleDim("Failed"));
    p.log.error(`${error}`);
  }
}

/**
 * Delete a user from Keycloak (cascade delete from NPL + services.yaml)
 */
async function deleteUserFromKeycloakFlow(kcUser: KeycloakUser, localUser: UserToolAccess | undefined): Promise<void> {
  const email = kcUser.email || "(no email)";
  const displayName = `${kcUser.firstName || ""} ${kcUser.lastName || ""}`.trim() || kcUser.username || email;
  
  console.log();
  console.log(noumena.warning("  ⚠ WARNING: This will delete the user's identity!"));
  console.log(noumena.textDim("  • Keycloak account will be removed"));
  console.log(noumena.textDim("  • NPL registration will be removed (if exists)"));
  console.log(noumena.textDim("  • services.yaml entry will be removed (if exists)"));
  console.log();
  
  const confirmed = await p.confirm({
    message: `Delete ${noumena.purple(displayName)} from Keycloak?`,
    initialValue: false,
  });

  if (p.isCancel(confirmed) || !confirmed) {
    return;
  }

  const s = p.spinner();

  try {
    // Step 1: Remove from NPL (if registered)
    if (localUser) {
      s.start("Removing from NPL...");
      try {
        await removeUserFromNpl(localUser.userId);
        s.stop(noumena.success("Removed from NPL"));
      } catch {
        s.stop(noumena.textDim("NPL removal skipped (not found)"));
      }
    }

    // Step 2: Delete from Keycloak
    if (kcUser.id) {
      s.start("Deleting from Keycloak...");
      try {
        await deleteKeycloakUser(kcUser.id);
        s.stop(noumena.success("Deleted from Keycloak"));
      } catch (error) {
        s.stop(noumena.warning("Failed to delete from Keycloak"));
        p.log.warn(`${error}`);
      }
    } else {
      s.stop(noumena.warning("Cannot delete from Keycloak (missing ID)"));
    }

    // Step 3: Remove from config (if exists)
    if (localUser) {
      s.start("Removing from configuration...");
      const success = removeUserFromConfig(localUser.userId);
      if (success) {
        s.stop(noumena.success("Removed from configuration"));
      } else {
        s.stop(noumena.purpleDim("Failed to update configuration"));
      }
    }
    
    p.log.success(`User deleted: ${email}`);
  } catch (error) {
    s.stop(noumena.purpleDim("Failed"));
    p.log.error(`${error}`);
  }
}

/**
 * User actions menu (legacy, kept for compatibility)
 */
async function userActionsFlow(user: UserToolAccess): Promise<void> {
  console.log();
  console.log(noumena.purple(`  ${user.displayName || user.userId}`));
  console.log(noumena.textDim(`  Email: ${user.userId}`));
  if (user.keycloakId) {
    console.log(noumena.textDim(`  Keycloak ID: ${user.keycloakId}`));
  }
  
  const serviceCount = Object.keys(user.tools).length;
  const toolCount = Object.values(user.tools).reduce((sum, tools) => sum + tools.length, 0);
  console.log(noumena.textDim(`  Access: ${serviceCount} services, ${toolCount} tools`));
  console.log();

  const action = await p.select({
    message: "Action:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      { value: "tools", label: "Edit tool access", hint: "Grant/revoke tools" },
      { value: "view", label: "View all access", hint: "Show detailed permissions" },
      { value: "delete", label: noumena.purpleDim("Delete user"), hint: "Remove from Keycloak + NPL + services.yaml" },
    ],
  });

  if (p.isCancel(action) || action === "back") {
    return;
  }

  if (action === "tools") {
    await editUserToolAccessFlow(user);
  } else if (action === "view") {
    await viewUserAccessFlow(user);
  } else if (action === "delete") {
    await deleteUserFlow(user);
  }
}

/**
 * Edit tool access for a user
 */
async function editUserToolAccessFlow(user: UserToolAccess): Promise<void> {
  const config = loadConfig();
  const services = config.services.filter(s => s.enabled);

  if (services.length === 0) {
    p.log.warn("No services are enabled");
    return;
  }

  while (true) {
    // Refresh user data
    const freshUser = getUser(user.userId);
    if (!freshUser) break;

    console.log();
    console.log(noumena.purple(`  Tool Access: ${freshUser.displayName || freshUser.userId}`));
    console.log();

    // Show services with access status
    const serviceOptions = services.map(service => {
      const hasAccess = service.name in freshUser.tools;
      const toolList = freshUser.tools[service.name] || [];
      const isWildcard = toolList.includes("*");
      const serviceDisabled = !service.enabled;
      
      let hint: string;
      if (isWildcard) {
        hint = serviceDisabled ? "All tools granted (service disabled)" : "All tools granted";
      } else if (hasAccess) {
        hint = serviceDisabled 
          ? `${toolList.length}/${service.tools.length} tools (service disabled)`
          : `${toolList.length}/${service.tools.length} tools`;
      } else {
        hint = "No access";
      }

      const icon = hasAccess ? noumena.success("✓") : noumena.grayDim("–");
      const serviceLabel = serviceDisabled 
        ? `${service.displayName} ${noumena.grayDim("(disabled)")}`
        : service.displayName;
      
      return {
        value: service.name,
        label: `${icon}  ${serviceLabel}`,
        hint,
      };
    });

    const selected = await p.select({
      message: "Select a service to manage:",
      options: [
        { value: "back", label: noumena.textDim("← Back") },
        ...serviceOptions,
      ],
    });

    if (p.isCancel(selected) || selected === "back") {
      break;
    }

    const service = services.find(s => s.name === selected);
    if (service) {
      await manageUserServiceAccessFlow(freshUser, service);
    }
  }
}

/**
 * Manage user access for a specific service
 */
async function manageUserServiceAccessFlow(
  user: UserToolAccess,
  service: ServiceDefinition
): Promise<void> {
  while (true) {
    // Refresh user data
    const freshUser = getUser(user.userId);
    if (!freshUser) break;

    const userTools = freshUser.tools[service.name] || [];
    const hasWildcard = userTools.includes("*");
    const enabledCount = hasWildcard ? service.tools.length : userTools.length;
    const serviceDisabled = !service.enabled;

    console.log();
    console.log(noumena.purple(`  ${service.displayName} - ${freshUser.displayName || freshUser.userId}`));
    console.log(noumena.textDim(`  ${enabledCount}/${service.tools.length} tools granted`));
    if (serviceDisabled) {
      console.log(noumena.warning("  ⚠ Service is globally disabled - tools cannot be used until enabled"));
    }
    console.log();

    const action = await p.select({
      message: "What do you want to do?",
      options: [
        { value: "back" as const, label: noumena.textDim("← Back") },
        { value: "grant" as const, label: noumena.success("Grant tools"), hint: "Pick tools with SPACE, then ENTER" },
        { value: "revoke" as const, label: noumena.warning("Revoke tools"), hint: "Pick tools with SPACE, then ENTER" },
        { value: "grant_all" as const, label: "Grant all", hint: "Grant all tools at once" },
        { value: "revoke_all" as const, label: "Revoke all", hint: "Remove all access to this service" },
      ],
    });

    if (p.isCancel(action) || action === "back") {
      break;
    }

    const s = p.spinner();

    if (action === "grant_all") {
      s.start(`Granting all ${service.displayName} tools...`);
      grantAllToolsToUser(freshUser.userId, service.name);
      try {
        await grantAllToolsForServiceInNpl(freshUser.userId, service.name);
        s.stop(noumena.success("All tools granted and synced to NPL"));
      } catch (err) {
        s.stop(noumena.warning(`All tools granted locally (NPL sync failed: ${err})`));
        console.log(noumena.textDim("  Run 'Import from YAML' from main menu to sync"));
      }
      continue;
    }

    if (action === "revoke_all") {
      const confirmed = await p.confirm({
        message: `Revoke ALL access to ${service.displayName}?`,
        initialValue: false,
      });

      if (!p.isCancel(confirmed) && confirmed) {
        s.start("Revoking access...");
        revokeServiceFromUser(freshUser.userId, service.name);
        try {
          await revokeServiceInNpl(freshUser.userId, service.name);
          s.stop(noumena.success("Service access revoked and synced to NPL"));
        } catch (err) {
          s.stop(noumena.warning(`Service access revoked locally (NPL sync failed: ${err})`));
          console.log(noumena.textDim("  Run 'Import from YAML' from main menu to sync"));
        }
      }
      continue;
    }

    // Filter tools based on action
    const toolsToShow = action === "grant"
      ? service.tools.filter(t => !hasWildcard && !userTools.includes(t.name))
      : service.tools.filter(t => hasWildcard || userTools.includes(t.name));

    if (toolsToShow.length === 0) {
      p.log.info(action === "grant" ? "All tools are already granted" : "No tools to revoke");
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

    // Apply changes — save to local config first, then sync to NPL
    s.start(`${action === "grant" ? "Granting" : "Revoking"} ${selectedTools.length} tool(s)...`);
    
    // Step 1: Always save to local config
    for (const toolName of selectedTools) {
      if (action === "grant") {
        grantToolToUser(freshUser.userId, service.name, toolName);
      } else {
        revokeToolFromUser(freshUser.userId, service.name, toolName);
      }
    }

    // Step 2: Best-effort sync to NPL
    let nplSynced = true;
    let nplError = "";
    try {
      for (const toolName of selectedTools) {
        if (action === "grant") {
          await grantToolInNpl(freshUser.userId, service.name, toolName);
        } else {
          await revokeToolInNpl(freshUser.userId, service.name, toolName);
        }
      }
    } catch (err) {
      nplSynced = false;
      nplError = String(err);
    }

    if (nplSynced) {
      s.stop(noumena.success(`${selectedTools.length} tool(s) ${action === "grant" ? "granted" : "revoked"} and synced to NPL`));
    } else {
      s.stop(noumena.warning(`${selectedTools.length} tool(s) ${action === "grant" ? "granted" : "revoked"} locally (NPL sync failed)`));
      console.log(noumena.textDim(`  Error: ${nplError.substring(0, 100)}`));
      console.log(noumena.textDim("  Run 'Sync NPL' from main menu to sync"));
    }
  }
}

/**
 * View detailed access for a user
 */
async function viewUserAccessFlow(user: UserToolAccess): Promise<void> {
  console.log();
  console.log(noumena.purple(`  Access Details: ${user.displayName || user.userId}`));
  console.log();

  if (Object.keys(user.tools).length === 0) {
    console.log(noumena.textDim("  No tool access granted"));
  } else {
    for (const [serviceName, tools] of Object.entries(user.tools)) {
      const service = loadConfig().services.find(s => s.name === serviceName);
      const displayName = service?.displayName || serviceName;
      
      if (tools.includes("*")) {
        console.log(noumena.success(`  ✓ ${displayName}`));
        console.log(noumena.textDim(`    ALL TOOLS`));
      } else {
        console.log(noumena.success(`  ✓ ${displayName}`));
        tools.forEach(tool => {
          console.log(noumena.textDim(`    - ${tool}`));
        });
      }
      console.log();
    }
  }

  await p.confirm({ message: "Press Enter to continue", initialValue: true });
}

/**
 * Delete a user
 */
async function deleteUserFlow(user: UserToolAccess): Promise<void> {
  const confirmed = await p.confirm({
    message: `Delete ${noumena.purple(user.displayName || user.userId)}? This will remove them from Keycloak and NPL.`,
    initialValue: false,
  });

  if (p.isCancel(confirmed) || !confirmed) {
    return;
  }

  const s = p.spinner();

  try {
    // Step 1: Remove from NPL
    s.start("Removing from NPL...");
    try {
      await removeUserFromNpl(user.userId);
      s.stop(noumena.success("Removed from NPL"));
    } catch {
      s.stop(noumena.textDim("NPL removal skipped (not found)"));
    }

    // Step 2: Delete from Keycloak (if we have the ID)
    if (user.keycloakId) {
      s.start("Deleting from Keycloak...");
      try {
        await deleteKeycloakUser(user.keycloakId);
        s.stop(noumena.success("Deleted from Keycloak"));
      } catch (error) {
        s.stop(noumena.warning("Failed to delete from Keycloak"));
        p.log.warn(`${error}`);
      }
    }

    // Step 3: Remove from config
    s.start("Removing from configuration...");
    const success = removeUserFromConfig(user.userId);
    if (success) {
      s.stop(noumena.success("Removed from configuration"));
      p.log.success(`User deleted: ${user.userId}`);
    } else {
      s.stop(noumena.purpleDim("Failed to update configuration"));
    }
  } catch (error) {
    s.stop(noumena.purpleDim("Failed"));
    p.log.error(`${error}`);
  }
}

/**
 * Credential Management Flow
 */
async function credentialManagementFlow(): Promise<void> {
  const credConfig = loadCredentialsConfig();
  const services = loadConfig().services;
  
  console.log();
  console.log(noumena.purple("  Credential Management"));
  console.log(noumena.textDim("  Manage Vault secrets and credential mappings"));
  console.log();
  
  // Show current status
  const credCount = Object.keys(credConfig.credentials).length;
  const servicesWithCreds = Object.keys(credConfig.service_defaults).length;
  
  console.log(noumena.textDim(`  Credentials defined: ${credCount}`));
  console.log(noumena.textDim(`  Services with credentials: ${servicesWithCreds}/${services.length}`));
  console.log();
  
  // Show quick guide if first time
  if (credCount === 0) {
    console.log(noumena.success("  💡 Quick Start Guide:"));
    console.log(noumena.textDim("     1. Add credential - Define how to fetch secrets from Vault"));
    console.log(noumena.textDim("     2. Configure service - Map a service to use the credential"));
    console.log(noumena.textDim("     3. Test injection - Verify it works before making real calls"));
    console.log();
  }
  
  const action = await p.select({
    message: "Select action:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      { value: "add", label: noumena.accent("  + Add credential"), hint: "Create new credential mapping" },
      { value: "configure", label: "  Configure service", hint: "Set up credentials for a service" },
      { value: "test", label: "  Test injection", hint: "Verify credential injection works" },
      { value: "view", label: "  View credentials.yaml", hint: "Show current configuration" },
    ],
  });
  
  if (p.isCancel(action) || action === "back") {
    return;
  }
  
  if (action === "add") {
    await addCredentialFlow();
  } else if (action === "configure") {
    await configureServiceCredentialsFlow();
  } else if (action === "test") {
    await testCredentialsFlow();
  } else if (action === "view") {
    await viewCredentialsConfigFlow();
  }
}

// Service configuration lookup table for common services
const SERVICE_CONFIGS: Record<string, {
  pathSuffix: string;
  envVarPrefix: string;
  fields: Array<{ vaultField: string; envVar: string }>;
  description: string;
}> = {
  gemini: {
    pathSuffix: "gemini/api",
    envVarPrefix: "GEMINI",
    fields: [{ vaultField: "api_key", envVar: "GEMINI_API_KEY" }],
    description: "Google Gemini AI API",
  },
  google_gemini: {
    pathSuffix: "gemini/api",
    envVarPrefix: "GEMINI",
    fields: [{ vaultField: "api_key", envVar: "GEMINI_API_KEY" }],
    description: "Google Gemini AI API",
  },
  github: {
    pathSuffix: "github/personal",
    envVarPrefix: "GITHUB",
    fields: [{ vaultField: "token", envVar: "GITHUB_TOKEN" }],
    description: "GitHub API",
  },
  work_github: {
    pathSuffix: "github/work",
    envVarPrefix: "GITHUB",
    fields: [{ vaultField: "token", envVar: "GITHUB_TOKEN" }],
    description: "GitHub API (work account)",
  },
  personal_github: {
    pathSuffix: "github/personal",
    envVarPrefix: "GITHUB",
    fields: [{ vaultField: "token", envVar: "GITHUB_TOKEN" }],
    description: "GitHub API (personal account)",
  },
  slack: {
    pathSuffix: "slack/workspace",
    envVarPrefix: "SLACK",
    fields: [{ vaultField: "token", envVar: "SLACK_TOKEN" }],
    description: "Slack API",
  },
  prod_slack: {
    pathSuffix: "slack/prod",
    envVarPrefix: "SLACK",
    fields: [{ vaultField: "token", envVar: "SLACK_TOKEN" }],
    description: "Slack API (production)",
  },
  openai: {
    pathSuffix: "openai/api",
    envVarPrefix: "OPENAI",
    fields: [{ vaultField: "api_key", envVar: "OPENAI_API_KEY" }],
    description: "OpenAI API",
  },
  anthropic: {
    pathSuffix: "anthropic/api",
    envVarPrefix: "ANTHROPIC",
    fields: [{ vaultField: "api_key", envVar: "ANTHROPIC_API_KEY" }],
    description: "Anthropic Claude API",
  },
  database: {
    pathSuffix: "database/credentials",
    envVarPrefix: "DB",
    fields: [
      { vaultField: "username", envVar: "DB_USER" },
      { vaultField: "password", envVar: "DB_PASS" },
    ],
    description: "Database credentials",
  },
};

/**
 * Detect service from credential name and return canonical config
 */
function detectService(credentialName: string): typeof SERVICE_CONFIGS[string] | null {
  // Direct match
  if (SERVICE_CONFIGS[credentialName]) {
    return SERVICE_CONFIGS[credentialName];
  }
  
  // Partial match (e.g., "my_gemini" contains "gemini")
  for (const [key, config] of Object.entries(SERVICE_CONFIGS)) {
    if (credentialName.includes(key)) {
      return config;
    }
  }
  
  return null;
}

/**
 * Add new credential mapping
 */
async function addCredentialFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  Add Credential Mapping"));
  console.log(noumena.textDim("  Create a new credential with Vault integration"));
  console.log();
  console.log(noumena.textDim("  💡 Common services: gemini, github, slack, openai, anthropic"));
  console.log();
  
  // Step 1: Credential name
  const credentialName = await p.text({
    message: "Credential name:",
    placeholder: "e.g., google_gemini, work_github, prod_slack",
    validate: (value) => {
      if (!value) return "Credential name is required";
      if (!/^[a-z0-9_]+$/.test(value)) return "Use lowercase, numbers, and underscores only";
      return undefined;
    },
  });
  
  if (p.isCancel(credentialName)) return;
  
  // Detect service and provide smart defaults
  const detectedService = detectService(String(credentialName));
  
  if (detectedService) {
    console.log();
    console.log(noumena.success(`  ✓ Detected: ${detectedService.description}`));
    console.log(noumena.textDim(`  Using canonical configuration for this service`));
    console.log();
  }
  
  // Step 2: Vault path template (with better suggestion)
  const suggestedPath = detectedService
    ? `secret/data/tenants/{tenant}/users/{user}/${detectedService.pathSuffix}`
    : `secret/data/tenants/{tenant}/users/{user}/${String(credentialName).replace(/_/g, "/")}`;
  
  console.log(noumena.textDim("  📁 Vault path templates:"));
  console.log(noumena.textDim("     • User-specific: secret/data/tenants/{tenant}/users/{user}/SERVICE/api"));
  console.log(noumena.textDim("     • Service-wide:  secret/data/tenants/{tenant}/services/SERVICE/env"));
  console.log();
  
  const vaultPath = await p.text({
    message: "Vault path template:",
    placeholder: "Use suggested path or enter custom",
    initialValue: suggestedPath,
    validate: (value) => {
      if (!value) return "Vault path is required";
      if (!value.startsWith("secret/data/")) return "Path should start with 'secret/data/'";
      if (!value.includes("{tenant}") || !value.includes("{user}")) {
        return "Path should include {tenant} and {user} for multi-tenancy";
      }
      return undefined;
    },
  });
  
  if (p.isCancel(vaultPath)) return;
  
  // Step 3: Injection type
  const injectionType = await p.select({
    message: "How should credentials be injected?",
    options: [
      { value: "env", label: "Environment variables", hint: "Most common for MCP servers" },
      { value: "header", label: "HTTP headers", hint: "For HTTP-based services" },
    ],
  });
  
  if (p.isCancel(injectionType)) return;
  
  // Step 4: Field mappings (with smart suggestions)
  console.log();
  console.log(noumena.textDim("  🔑 Define field mappings (Vault field → Injection target)"));
  
  const fieldMapping: Record<string, string> = {};
  
  // If we detected the service, suggest its standard fields
  if (detectedService && detectedService.fields.length > 0) {
    console.log(noumena.textDim(`  Standard fields for ${detectedService.description}:`));
    detectedService.fields.forEach(f => {
      console.log(noumena.textDim(`    • ${f.vaultField} → ${f.envVar}`));
    });
    console.log();
    
    const useStandard = await p.confirm({
      message: "Use standard field mappings?",
      initialValue: true,
    });
    
    if (!p.isCancel(useStandard) && useStandard) {
      // Use the standard mappings
      detectedService.fields.forEach(f => {
        fieldMapping[f.vaultField] = f.envVar;
      });
      console.log();
      p.log.success(`  ✓ Added ${detectedService.fields.length} standard field(s)`);
    } else {
      console.log();
      console.log(noumena.textDim("  Define custom field mappings (press Enter with empty field to finish)"));
      console.log();
    }
  } else {
    console.log(noumena.textDim("  Common fields: api_key, token, username, password"));
    console.log(noumena.textDim("  Press Enter with empty field name when done"));
    console.log();
  }
  
  // Allow adding custom fields (or all fields if not using standard)
  if (Object.keys(fieldMapping).length === 0 || detectedService === null) {
    while (true) {
      const vaultField = await p.text({
        message: `Vault field name (${Object.keys(fieldMapping).length} added):`,
        placeholder: "e.g., api_key, token, username, password",
      });
      
      if (p.isCancel(vaultField)) return;
      if (!vaultField) break; // Done adding fields
      
      // Smart env var suggestion based on field name and detected service
      let suggestedEnvVar = String(vaultField).toUpperCase();
      if (detectedService && injectionType === "env") {
        // Use service prefix for better naming
        const fieldName = String(vaultField).toUpperCase();
        suggestedEnvVar = `${detectedService.envVarPrefix}_${fieldName}`;
      }
      
      const targetName = await p.text({
        message: `  → ${injectionType === "env" ? "Environment variable" : "Header"} name:`,
        placeholder: injectionType === "env" ? "e.g., GEMINI_API_KEY, GITHUB_TOKEN" : "e.g., X-API-Key, Authorization",
        initialValue: suggestedEnvVar,
      });
      
      if (p.isCancel(targetName)) return;
      if (!targetName) continue;
      
      fieldMapping[String(vaultField)] = String(targetName);
      p.log.success(`  ✓ Added: ${vaultField} → ${targetName}`);
    }
  }
  
  if (Object.keys(fieldMapping).length === 0) {
    p.log.warn("No field mappings defined. Credential not added.");
    return;
  }
  
  // Step 5: Save mapping
  const s = p.spinner();
  s.start("Saving credential mapping...");
  
  const success = addCredentialMapping(
    String(credentialName),
    String(vaultPath),
    String(injectionType),
    fieldMapping
  );
  
  if (success) {
    s.stop(noumena.success("Credential mapping saved"));
    
    // Step 6: Ask if they want to store secrets now
    const storeNow = await p.confirm({
      message: "Store secrets in Vault now?",
      initialValue: true,
    });
    
    if (!p.isCancel(storeNow) && storeNow) {
      await storeSecretsInVaultFlow(String(credentialName), String(vaultPath), fieldMapping);
    } else {
      console.log();
      p.log.info("You can store secrets manually later:");
      console.log(noumena.textDim(`  docker exec gateway-vault-1 vault kv put ${vaultPath.replace("{tenant}", "default").replace("{user}", "alice")} ...`));
    }
  } else {
    s.stop(noumena.purpleDim("Failed to save"));
  }
}

/**
 * Store secrets in Vault
 */
async function storeSecretsInVaultFlow(
  credentialName: string,
  vaultPathTemplate: string,
  fieldMapping: Record<string, string>
): Promise<void> {
  console.log();
  console.log(noumena.purple("  Store Secrets in Vault"));
  console.log(noumena.textDim("  Securely store credential values for this mapping"));
  console.log();
  
  // Ask for tenant and user
  console.log(noumena.textDim("  💡 For development, use: tenant=default, user=alice"));
  console.log();
  
  const tenantId = await p.text({
    message: "Tenant ID:",
    initialValue: "default",
    validate: (v) => v ? undefined : "Tenant ID is required",
  });
  
  if (p.isCancel(tenantId)) return;
  
  const userId = await p.text({
    message: "User ID:",
    initialValue: "alice",
    validate: (v) => v ? undefined : "User ID is required",
  });
  
  if (p.isCancel(userId)) return;
  
  // Interpolate path
  const vaultPath = vaultPathTemplate
    .replace("{tenant}", String(tenantId))
    .replace("{user}", String(userId));
  
  console.log();
  console.log(noumena.success(`  ✓ Vault path: ${vaultPath}`));
  console.log();
  
  // Collect secret values
  const secretData: Record<string, string> = {};
  
  for (const [vaultField, targetName] of Object.entries(fieldMapping)) {
    const value = await p.password({
      message: `Enter ${vaultField}:`,
      mask: "*",
      validate: (v) => v ? undefined : "Value is required",
    });
    
    if (p.isCancel(value)) return;
    
    secretData[vaultField] = String(value);
  }
  
  // Store in Vault
  const s = p.spinner();
  s.start("Storing secrets in Vault...");
  
  try {
    await storeSecretInVault(vaultPath, secretData);
    s.stop(noumena.success("Secrets stored in Vault"));
    p.log.success(`Credential '${credentialName}' is ready to use`);
  } catch (error) {
    s.stop(noumena.purpleDim("Failed to store secrets"));
    p.log.error(`${error}`);
  }
}

/**
 * Configure service credentials
 */
async function configureServiceCredentialsFlow(): Promise<void> {
  const services = loadConfig().services;
  const credConfig = loadCredentialsConfig();
  
  console.log();
  console.log(noumena.purple("  Configure Service Credentials"));
  console.log();
  
  if (services.length === 0) {
    p.log.warn("No services configured yet");
    return;
  }
  
  // Select service
  const serviceName = await p.select({
    message: "Select service:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      ...services.map(s => ({
        value: s.name,
        label: s.displayName,
        hint: hasCredentials(s.name) ? noumena.success("✓ configured") : noumena.grayDim("no credentials"),
      })),
    ],
  });
  
  if (p.isCancel(serviceName) || serviceName === "back") return;
  
  const service = services.find(s => s.name === serviceName);
  if (!service) return;
  
  // Show available credentials
  const availableCredentials = Object.keys(credConfig.credentials);
  
  if (availableCredentials.length === 0) {
    p.log.warn("No credentials defined yet. Add a credential first.");
    return;
  }
  
  const credentialName = await p.select({
    message: `Select credential for ${service.displayName}:`,
    options: [
      { value: "none", label: noumena.grayDim("(none)"), hint: "Remove credential mapping" },
      ...availableCredentials.map(name => ({
        value: name,
        label: name,
        hint: credConfig.credentials[name].vault_path,
      })),
    ],
  });
  
  if (p.isCancel(credentialName)) return;
  
  if (credentialName === "none") {
    // Remove mapping
    const config = loadCredentialsConfig();
    delete config.service_defaults[String(serviceName)];
    saveCredentialsConfig(config);
    p.log.success(`Removed credential mapping for ${service.displayName}`);
  } else {
    // Set mapping
    const success = setServiceCredential(String(serviceName), String(credentialName));
    if (success) {
      p.log.success(`${service.displayName} will use '${credentialName}' credentials`);
      
      // Ask if they want to test
      const testNow = await p.confirm({
        message: "Test credential injection now?",
        initialValue: true,
      });
      
      if (!p.isCancel(testNow) && testNow) {
        await testSingleServiceCredential(String(serviceName));
      }
    }
  }
}

/**
 * Test credentials flow
 */
async function testCredentialsFlow(): Promise<void> {
  const services = loadConfig().services;
  const servicesWithCreds = services.filter(s => hasCredentials(s.name));
  
  console.log();
  console.log(noumena.purple("  Test Credential Injection"));
  console.log();
  
  if (servicesWithCreds.length === 0) {
    p.log.warn("No services with credentials configured");
    return;
  }
  
  const serviceName = await p.select({
    message: "Test credentials for:",
    options: [
      { value: "back", label: noumena.textDim("← Back") },
      { value: "all", label: noumena.purple("Test all services"), hint: `${servicesWithCreds.length} services` },
      ...servicesWithCreds.map(s => ({
        value: s.name,
        label: s.displayName,
        hint: getServiceCredential(s.name) || "",
      })),
    ],
  });
  
  if (p.isCancel(serviceName) || serviceName === "back") return;
  
  if (serviceName === "all") {
    for (const service of servicesWithCreds) {
      await testSingleServiceCredential(service.name);
    }
  } else {
    await testSingleServiceCredential(String(serviceName));
  }
}

/**
 * Test a single service's credentials
 */
async function testSingleServiceCredential(serviceName: string): Promise<void> {
  const service = loadConfig().services.find(s => s.name === serviceName);
  if (!service) return;
  
  const s = p.spinner();
  s.start(`Testing ${service.displayName}...`);
  
  try {
    const result = await testCredentialInjection(serviceName);
    s.stop(noumena.success(`${service.displayName}: ${result.credentialName}`));
    
    const fields = Object.entries(result.injectedFields);
    for (const [key, value] of fields) {
      const maskedValue = value.substring(0, 10) + "...";
      console.log(noumena.textDim(`    ${key}=${maskedValue}`));
    }
  } catch (error) {
    s.stop(noumena.purpleDim(`${service.displayName}: Failed`));
    p.log.warn(`${error}`);
  }
}

/**
 * View credentials configuration
 */
async function viewCredentialsConfigFlow(): Promise<void> {
  console.log();
  console.log(noumena.purple("  credentials.yaml"));
  console.log();
  
  try {
    const configPath = getConfigPath().replace("services.yaml", "credentials.yaml");
    const content = readFileSync(configPath, "utf-8");
    console.log(content);
  } catch {
    p.log.warn("credentials.yaml not found");
  }
  
  console.log();
  await p.text({
    message: "Press Enter to continue...",
    placeholder: "",
  });
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

  // Step 3: Auto-bootstrap NPL if needed
  const bs = p.spinner();
  bs.start("Checking NPL status...");
  const bootstrapped = await isNplBootstrapped();
  if (bootstrapped) {
    bs.stop(noumena.success("NPL ready"));
  } else {
    bs.stop(noumena.textDim("NPL not yet bootstrapped — setting up..."));
    try {
      await bootstrapNpl();
      p.log.success("NPL bootstrapped automatically");
    } catch {
      p.log.warn("NPL auto-sync failed (engine may not be running). You can retry from System > Import from YAML.");
    }
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
