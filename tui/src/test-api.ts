#!/usr/bin/env node
/**
 * Automated test for TUI → PolicyStore API integration.
 *
 * Tests every PolicyStore function end-to-end against a running NPL engine.
 * Run: npx tsx src/test-api.ts
 *
 * Prerequisites: docker compose stack running (npl-engine, keycloak)
 */

import {
  setAdminCredentials,
  validateCredentials,
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
  type PolicyData,
} from "./lib/api.js";

const TEST_SERVICE = "test-service-" + Date.now();
const TEST_USER = "testuser@test.com";

let passed = 0;
let failed = 0;

async function assert(name: string, fn: () => Promise<void>) {
  try {
    await fn();
    console.log(`  ✓ ${name}`);
    passed++;
  } catch (err) {
    console.log(`  ✗ ${name}`);
    console.log(`    ${err}`);
    failed++;
  }
}

function assertEqual(actual: unknown, expected: unknown, msg: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`${msg}: expected ${e}, got ${a}`);
  }
}

function assertIncludes(arr: string[], item: string, msg: string) {
  if (!arr.includes(item)) {
    throw new Error(`${msg}: expected array to include "${item}", got [${arr.join(", ")}]`);
  }
}

function assertNotIncludes(arr: string[], item: string, msg: string) {
  if (arr.includes(item)) {
    throw new Error(`${msg}: expected array to NOT include "${item}"`);
  }
}

async function main() {
  console.log("\n=== TUI API Integration Tests ===\n");

  // ── Auth ──
  console.log("Auth:");

  await assert("validate bad credentials returns false", async () => {
    const ok = await validateCredentials("admin", "wrongpassword");
    assertEqual(ok, false, "should reject bad password");
  });

  await assert("validate good credentials returns true", async () => {
    const ok = await validateCredentials("admin", "Welcome123");
    assertEqual(ok, true, "should accept correct password");
  });

  await assert("setAdminCredentials + ensurePolicyStore", async () => {
    setAdminCredentials("admin", "Welcome123");
    const id = await ensurePolicyStore();
    if (!id || typeof id !== "string" || id.length < 10) {
      throw new Error(`Expected UUID, got: ${id}`);
    }
  });

  // ── Read initial state ──
  console.log("\nRead:");

  let initialData: PolicyData;
  await assert("getPolicyData returns valid structure", async () => {
    initialData = await getPolicyData();
    if (typeof initialData.services !== "object") throw new Error("services not an object");
    if (typeof initialData.grants !== "object") throw new Error("grants not an object");
    if (!Array.isArray(initialData.revokedSubjects)) throw new Error("revokedSubjects not an array");
    if (typeof initialData.contextualRoutes !== "object") throw new Error("contextualRoutes not an object");
  });

  // ── Service lifecycle ──
  console.log("\nService lifecycle:");

  await assert("registerService creates a new service", async () => {
    await registerService(TEST_SERVICE);
    const data = await getPolicyData();
    const svc = data.services[TEST_SERVICE];
    if (!svc) throw new Error(`Service "${TEST_SERVICE}" not found after register`);
    assertEqual(svc.serviceName, TEST_SERVICE, "serviceName");
    assertEqual(svc.enabled, false, "should be disabled by default");
    assertEqual(svc.suspended, false, "should not be suspended");
    assertEqual(svc.enabledTools.length, 0, "should have no tools");
  });

  await assert("registerService rejects duplicate", async () => {
    try {
      await registerService(TEST_SERVICE);
      throw new Error("Should have thrown");
    } catch (err: any) {
      if (!err.message.includes("already registered") && !err.message.includes("failed")) {
        throw err;
      }
    }
  });

  await assert("enableService sets enabled=true", async () => {
    await enableService(TEST_SERVICE);
    const data = await getPolicyData();
    assertEqual(data.services[TEST_SERVICE].enabled, true, "enabled");
  });

  await assert("disableService sets enabled=false", async () => {
    await disableService(TEST_SERVICE);
    const data = await getPolicyData();
    assertEqual(data.services[TEST_SERVICE].enabled, false, "enabled");
  });

  await assert("suspendService sets suspended=true", async () => {
    await suspendService(TEST_SERVICE);
    const data = await getPolicyData();
    assertEqual(data.services[TEST_SERVICE].suspended, true, "suspended");
  });

  await assert("resumeService sets suspended=false", async () => {
    await resumeService(TEST_SERVICE);
    const data = await getPolicyData();
    assertEqual(data.services[TEST_SERVICE].suspended, false, "suspended");
  });

  // ── Metadata ──
  console.log("\nMetadata:");

  await assert("setServiceMetadata stores key-value", async () => {
    await setServiceMetadata(TEST_SERVICE, "displayName", "Test Service");
    await setServiceMetadata(TEST_SERVICE, "type", "MCP_STDIO");
    await setServiceMetadata(TEST_SERVICE, "command", "docker run -i --rm test-image");
    await setServiceMetadata(TEST_SERVICE, "description", "A test service");
    const data = await getPolicyData();
    const meta = data.services[TEST_SERVICE].metadata;
    assertEqual(meta.displayName, "Test Service", "displayName");
    assertEqual(meta.type, "MCP_STDIO", "type");
    assertEqual(meta.command, "docker run -i --rm test-image", "command");
    assertEqual(meta.description, "A test service", "description");
  });

  await assert("setServiceMetadata overwrites existing key", async () => {
    await setServiceMetadata(TEST_SERVICE, "displayName", "Updated Name");
    const data = await getPolicyData();
    assertEqual(data.services[TEST_SERVICE].metadata.displayName, "Updated Name", "displayName");
  });

  // ── Tools ──
  console.log("\nTools:");

  await assert("enableTool adds tool to enabledTools", async () => {
    await enableTool(TEST_SERVICE, "search");
    await enableTool(TEST_SERVICE, "fetch");
    const data = await getPolicyData();
    const tools = data.services[TEST_SERVICE].enabledTools;
    assertIncludes(tools, "search", "enabledTools");
    assertIncludes(tools, "fetch", "enabledTools");
  });

  await assert("enableTool is idempotent", async () => {
    await enableTool(TEST_SERVICE, "search");
    const data = await getPolicyData();
    const count = data.services[TEST_SERVICE].enabledTools.filter((t) => t === "search").length;
    assertEqual(count, 1, "should not duplicate");
  });

  await assert("disableTool removes tool from enabledTools", async () => {
    await disableTool(TEST_SERVICE, "fetch");
    const data = await getPolicyData();
    assertNotIncludes(data.services[TEST_SERVICE].enabledTools, "fetch", "enabledTools");
    assertIncludes(data.services[TEST_SERVICE].enabledTools, "search", "enabledTools");
  });

  // ── Grants ──
  console.log("\nGrants:");

  await assert("grantTool grants specific tool to user", async () => {
    await grantTool(TEST_USER, TEST_SERVICE, "search");
    const data = await getPolicyData();
    const grants = data.grants[TEST_USER];
    if (!grants) throw new Error("No grants for test user");
    const grant = grants.find((g) => g.serviceName === TEST_SERVICE);
    if (!grant) throw new Error("No grant for test service");
    assertIncludes(grant.allowedTools, "search", "allowedTools");
  });

  await assert("grantTool adds another tool", async () => {
    await grantTool(TEST_USER, TEST_SERVICE, "fetch");
    const data = await getPolicyData();
    const grant = data.grants[TEST_USER]?.find((g) => g.serviceName === TEST_SERVICE);
    if (!grant) throw new Error("No grant found");
    assertIncludes(grant.allowedTools, "search", "allowedTools");
    assertIncludes(grant.allowedTools, "fetch", "allowedTools");
  });

  await assert("revokeTool removes specific tool from grant", async () => {
    await revokeTool(TEST_USER, TEST_SERVICE, "fetch");
    const data = await getPolicyData();
    const grant = data.grants[TEST_USER]?.find((g) => g.serviceName === TEST_SERVICE);
    if (!grant) throw new Error("No grant found");
    assertIncludes(grant.allowedTools, "search", "search should remain");
    assertNotIncludes(grant.allowedTools, "fetch", "fetch should be removed");
  });

  await assert("grantAllToolsForService sets wildcard", async () => {
    await grantAllToolsForService(TEST_USER, TEST_SERVICE);
    const data = await getPolicyData();
    const grant = data.grants[TEST_USER]?.find((g) => g.serviceName === TEST_SERVICE);
    if (!grant) throw new Error("No grant found");
    assertIncludes(grant.allowedTools, "*", "should have wildcard");
  });

  await assert("revokeServiceAccess removes all grants for service", async () => {
    await revokeServiceAccess(TEST_USER, TEST_SERVICE);
    const data = await getPolicyData();
    const grant = data.grants[TEST_USER]?.find((g) => g.serviceName === TEST_SERVICE);
    if (grant) throw new Error("Grant should have been removed");
  });

  // ── Subject revocation ──
  console.log("\nSubject revocation:");

  await assert("revokeSubject adds to revokedSubjects", async () => {
    await revokeSubject(TEST_USER);
    const data = await getPolicyData();
    assertIncludes(data.revokedSubjects, TEST_USER, "revokedSubjects");
  });

  await assert("reinstateSubject removes from revokedSubjects", async () => {
    await reinstateSubject(TEST_USER);
    const data = await getPolicyData();
    assertNotIncludes(data.revokedSubjects, TEST_USER, "revokedSubjects");
  });

  // ── Cleanup ──
  console.log("\nCleanup:");

  await assert("removeServiceFromPolicyStore deletes service", async () => {
    await removeServiceFromPolicyStore(TEST_SERVICE);
    const data = await getPolicyData();
    if (data.services[TEST_SERVICE]) {
      throw new Error("Service should have been removed");
    }
  });

  await assert("removeService rejects unknown service", async () => {
    try {
      await removeServiceFromPolicyStore("nonexistent-service-xyz");
      throw new Error("Should have thrown");
    } catch (err: any) {
      if (!err.message.includes("not found") && !err.message.includes("failed")) {
        throw err;
      }
    }
  });

  // ── Import/Export ──
  console.log("\nImport/Export:");

  await assert("importFromYaml imports services and grants", async () => {
    const result = await importFromYaml();
    if (result.servicesImported.length === 0) {
      throw new Error("No services imported — is services.yaml present?");
    }
    // Verify services exist in PolicyStore
    const data = await getPolicyData();
    for (const name of result.servicesImported) {
      if (!data.services[name]) {
        throw new Error(`Imported service "${name}" not found in PolicyStore`);
      }
    }
  });

  await assert("exportToYaml writes PolicyStore to YAML", async () => {
    await exportToYaml();
    // If it doesn't throw, it succeeded
  });

  // ── Verify final state ──
  console.log("\nFinal state:");

  await assert("getPolicyData shows imported services", async () => {
    const data = await getPolicyData();
    const svcNames = Object.keys(data.services);
    console.log(`    Services: ${svcNames.join(", ")}`);
    const grantKeys = Object.keys(data.grants);
    console.log(`    Grants: ${grantKeys.join(", ")}`);
    console.log(`    Revoked: ${data.revokedSubjects.join(", ") || "(none)"}`);
  });

  // ── Summary ──
  console.log(`\n${"=".repeat(40)}`);
  console.log(`  ${passed} passed, ${failed} failed`);
  console.log(`${"=".repeat(40)}\n`);

  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error("\nFatal error:", err);
  process.exit(1);
});
