/**
 * Tests for security-policy.ts — YAML validation, profile resolution, merging.
 *
 * Run with: node --import tsx src/lib/security-policy.test.ts
 */

import assert from "node:assert/strict";
import {
  validateConfig,
  resolveProfile,
  interpolateTenantVars,
  mergeProfiles,
  loadSecurityPolicyFile,
  processSecurityPolicy,
  type SecurityPolicyConfig,
} from "./security-policy.js";

let passed = 0;
let failed = 0;

function test(name: string, fn: () => void) {
  try {
    fn();
    passed++;
    console.log(`  \x1b[32m✓\x1b[0m ${name}`);
  } catch (e) {
    failed++;
    console.log(`  \x1b[31m✗\x1b[0m ${name}`);
    console.log(`    ${e}`);
  }
}

console.log("\nSecurity Policy Tests\n");

// ============================================================================
// Validation
// ============================================================================

console.log("Validation:");

test("valid config passes validation", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tenant: { company_domain: "acme.com" },
    profiles: ["@openclaw/security-gmail"],
    policies: [
      {
        name: "Allow read-only",
        when: { readOnlyHint: true },
        action: "allow",
        priority: 50,
      },
    ],
  };
  const errors = validateConfig(config);
  assert.equal(errors.length, 0, `Expected no errors, got: ${errors.join(", ")}`);
});

test("wrong version fails", () => {
  const config: SecurityPolicyConfig = { version: "2.0" };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes("version")));
});

test("invalid profile name fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    profiles: ["invalid-profile"],
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes("invalid profile name")));
});

test("invalid verb fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tool_overrides: {
      gmail: {
        send_email: { verb: "execute" as any },
      },
    },
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes('invalid verb "execute"')));
});

test("invalid label format fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tool_overrides: {
      gmail: {
        send_email: { labels: ["INVALID"] },
      },
    },
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes('invalid label "INVALID"')));
});

test("valid labels pass", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tool_overrides: {
      gmail: {
        send_email: { labels: ["scope:internal", "category:communication", "data:pii"] },
      },
    },
  };
  const errors = validateConfig(config);
  assert.equal(errors.length, 0);
});

test("require_approval without approvers fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    policies: [
      {
        name: "Broken",
        when: { destructiveHint: true },
        action: "require_approval",
        priority: 10,
      },
    ],
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes("approvers")));
});

test("policy priority out of range fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    policies: [
      {
        name: "Bad priority",
        when: {},
        action: "allow",
        priority: 1000,
      },
    ],
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes("priority")));
});

test("invalid action fails", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    policies: [
      {
        name: "Bad action",
        when: {},
        action: "block" as any,
        priority: 0,
      },
    ],
  };
  const errors = validateConfig(config);
  assert.ok(errors.some((e) => e.includes('invalid action "block"')));
});

// ============================================================================
// Tenant variable interpolation
// ============================================================================

console.log("\nTenant Interpolation:");

test("interpolates string values", () => {
  const result = interpolateTenantVars("@{company_domain}", { company_domain: "acme.com" });
  assert.equal(result, "@acme.com");
});

test("interpolates nested objects", () => {
  const input = {
    rules: [{ field: "to", contains: "@{company_domain}", set_labels: ["scope:internal"] }],
  };
  const result = interpolateTenantVars(input, { company_domain: "acme.com" }) as any;
  assert.equal(result.rules[0].contains, "@acme.com");
});

test("leaves non-matching placeholders alone", () => {
  const result = interpolateTenantVars("{unknown_var}", { company_domain: "acme.com" });
  assert.equal(result, "{unknown_var}");
});

test("handles null and numbers", () => {
  assert.equal(interpolateTenantVars(null, { x: "y" }), null);
  assert.equal(interpolateTenantVars(42, { x: "y" }), 42);
  assert.equal(interpolateTenantVars(true, { x: "y" }), true);
});

// ============================================================================
// Profile resolution
// ============================================================================

console.log("\nProfile Resolution:");

test("resolves gmail profile", () => {
  const profile = resolveProfile("@openclaw/security-gmail");
  assert.equal(profile.service, "gmail");
  assert.ok(profile.tools.send_email, "send_email tool should exist");
  assert.equal(profile.tools.send_email.verb, "create");
  assert.equal(profile.tools.send_email.openWorldHint, true);
  assert.ok(profile.tools.read_email, "read_email tool should exist");
  assert.equal(profile.tools.read_email.readOnlyHint, true);
});

test("rejects invalid profile name", () => {
  assert.throws(() => resolveProfile("not-a-profile"), /Invalid profile name/);
});

test("rejects missing profile", () => {
  assert.throws(() => resolveProfile("@openclaw/security-nonexistent"), /Profile not found/);
});

// ============================================================================
// Merging
// ============================================================================

console.log("\nMerging:");

test("merges gmail profile with overrides", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tenant: { company_domain: "acme.com" },
    profiles: ["@openclaw/security-gmail"],
    tool_overrides: {
      gmail: {
        send_email: { destructiveHint: true },
      },
    },
    policies: [
      { name: "Default allow", when: {}, action: "allow", priority: 999 },
    ],
  };

  const merged = mergeProfiles(config);

  // Profile values preserved
  assert.equal(merged.tool_annotations.gmail.send_email.verb, "create");
  assert.equal(merged.tool_annotations.gmail.send_email.annotations.openWorldHint, true);
  // Override applied
  assert.equal(merged.tool_annotations.gmail.send_email.annotations.destructiveHint, true);
  // Classifier tenant vars interpolated
  const rules = merged.classifiers.gmail?.send_email;
  assert.ok(rules, "send_email classifiers should exist");
  assert.ok(rules.some((r) => r.contains === "@acme.com"), "should interpolate {company_domain}");
});

test("override creates new tool annotation", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    tool_overrides: {
      custom: {
        my_tool: { readOnlyHint: true, verb: "get" },
      },
    },
  };

  const merged = mergeProfiles(config);
  assert.ok(merged.tool_annotations.custom.my_tool);
  assert.equal(merged.tool_annotations.custom.my_tool.annotations.readOnlyHint, true);
  assert.equal(merged.tool_annotations.custom.my_tool.verb, "get");
});

test("policies come from config not profiles", () => {
  const config: SecurityPolicyConfig = {
    version: "1.0",
    profiles: ["@openclaw/security-gmail"],
    policies: [
      { name: "Rule 1", when: {}, action: "allow", priority: 50 },
      { name: "Rule 2", when: {}, action: "deny", priority: 999 },
    ],
  };

  const merged = mergeProfiles(config);
  assert.equal(merged.policies.length, 2);
  assert.equal(merged.policies[0].name, "Rule 1");
});

test("empty config produces empty merged policy", () => {
  const config: SecurityPolicyConfig = { version: "1.0" };
  const merged = mergeProfiles(config);
  assert.equal(Object.keys(merged.tool_annotations).length, 0);
  assert.equal(Object.keys(merged.classifiers).length, 0);
  assert.equal(merged.policies.length, 0);
});

// ============================================================================
// Full pipeline (processSecurityPolicy)
// ============================================================================

console.log("\nFull Pipeline:");

test("processes acme-corp example", () => {
  // Resolve relative to project root (tui/src/lib/ → 3 levels up)
  const projectRoot = new URL("../../../", import.meta.url).pathname;
  const result = processSecurityPolicy(projectRoot + "security-profiles/examples/acme-corp.yaml");

  assert.equal(result.merged.version, "1.0");
  assert.ok(result.merged.tool_annotations.gmail, "should have gmail tools");
  assert.ok(result.merged.policies.length > 0, "should have policies");
  assert.ok(result.stats.toolAnnotations > 0, "should count tool annotations");
  assert.ok(result.stats.policyRules > 0, "should count policy rules");
  assert.ok(result.stats.bytes > 0, "should have non-zero bytes");
  assert.ok(result.json.length > 0, "should produce JSON");

  // Verify tenant interpolation happened
  const sendEmailClassifiers = result.merged.classifiers.gmail?.send_email;
  assert.ok(sendEmailClassifiers, "should have send_email classifiers");
  assert.ok(
    sendEmailClassifiers.some((r) => r.contains === "@acme.com"),
    "should interpolate company_domain to acme.com"
  );
});

// ============================================================================
// Summary
// ============================================================================

console.log(`\n${passed + failed} tests: ${passed} passed, ${failed} failed\n`);
if (failed > 0) process.exit(1);
