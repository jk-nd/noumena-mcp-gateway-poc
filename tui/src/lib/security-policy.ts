/**
 * Security Policy — parse, validate, merge, and apply mcp-security.yaml
 *
 * Flow:
 *   1. Read mcp-security.yaml
 *   2. Validate raw YAML against JSON Schema
 *   3. Resolve community profile imports (local YAML files)
 *   4. Interpolate tenant variables into classifier patterns
 *   5. Merge profiles + operator overrides (overrides win)
 *   6. Serialize to JSON → push to PolicyStore via setSecurityPolicy()
 */

import { readFileSync, existsSync } from "fs";
import { resolve, dirname } from "path";
import { parse as parseYaml } from "yaml";
import { fileURLToPath } from "url";

// --- Types ---

export interface SecurityPolicyConfig {
  version: string;
  tenant?: Record<string, string>;
  profiles?: string[];
  tool_overrides?: Record<string, Record<string, ToolOverride>>;
  classifiers?: Record<string, Record<string, ClassifierRule[]>>;
  policies?: PolicyRule[];
}

export interface ToolOverride {
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
  verb?: string;
  labels?: string[];
}

export interface ClassifierRule {
  field: string;
  contains?: string;
  not_contains?: string;
  present?: boolean;
  set_labels: string[];
}

export interface PolicyRule {
  name: string;
  description?: string;
  when: PolicyCondition;
  match?: "all" | "any";
  action: "allow" | "deny" | "npl_evaluate";
  approvers?: string[];
  timeout?: string;
  priority: number;
}

export interface PolicyCondition {
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
  verb?: string;
  labels?: string[];
}

export interface CommunityProfile {
  service: string;
  description?: string;
  tools: Record<string, ProfileToolDef>;
}

export interface ProfileToolDef {
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
  verb?: string;
  labels?: string[];
  classify?: ClassifierRule[];
}

export interface MergedToolAnnotation {
  annotations: Record<string, boolean>;
  verb: string | null;
  labels: string[];
}

export interface MergedSecurityPolicy {
  version: string;
  tool_annotations: Record<string, Record<string, MergedToolAnnotation>>;
  classifiers: Record<string, Record<string, ClassifierRule[]>>;
  policies: PolicyRule[];
}

// --- Validation ---

const VALID_VERBS = new Set(["get", "list", "create", "update", "delete"]);
const VALID_ACTIONS = new Set(["allow", "deny", "npl_evaluate"]);
const VALID_HINTS = new Set(["readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"]);
const LABEL_PATTERN = /^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$/;
const PROFILE_PATTERN = /^@[a-z0-9-]+\/security-([a-z0-9-]+)$/;

export function validateConfig(config: SecurityPolicyConfig): string[] {
  const errors: string[] = [];

  if (config.version !== "1.0") {
    errors.push(`version: must be "1.0", got "${config.version}"`);
  }

  // Validate profiles
  for (const profile of config.profiles ?? []) {
    if (!PROFILE_PATTERN.test(profile)) {
      errors.push(`profiles: invalid profile name "${profile}" (expected @org/security-<service>)`);
    }
  }

  // Validate tool_overrides
  for (const [service, tools] of Object.entries(config.tool_overrides ?? {})) {
    for (const [toolName, override] of Object.entries(tools)) {
      if (override.verb && !VALID_VERBS.has(override.verb)) {
        errors.push(`tool_overrides.${service}.${toolName}.verb: invalid verb "${override.verb}"`);
      }
      for (const label of override.labels ?? []) {
        if (!LABEL_PATTERN.test(label)) {
          errors.push(`tool_overrides.${service}.${toolName}.labels: invalid label "${label}"`);
        }
      }
    }
  }

  // Validate classifiers
  for (const [service, tools] of Object.entries(config.classifiers ?? {})) {
    for (const [toolName, rules] of Object.entries(tools)) {
      for (let i = 0; i < rules.length; i++) {
        const rule = rules[i];
        if (!rule.field) {
          errors.push(`classifiers.${service}.${toolName}[${i}]: missing "field"`);
        }
        if (!rule.set_labels?.length) {
          errors.push(`classifiers.${service}.${toolName}[${i}]: missing "set_labels"`);
        }
        for (const label of rule.set_labels ?? []) {
          if (!LABEL_PATTERN.test(label)) {
            errors.push(`classifiers.${service}.${toolName}[${i}].set_labels: invalid label "${label}"`);
          }
        }
      }
    }
  }

  // Validate policies
  for (let i = 0; i < (config.policies ?? []).length; i++) {
    const policy = config.policies![i];
    if (!policy.name) {
      errors.push(`policies[${i}]: missing "name"`);
    }
    if (!VALID_ACTIONS.has(policy.action)) {
      errors.push(`policies[${i}].action: invalid action "${policy.action}"`);
    }
    if (policy.action === "npl_evaluate" && policy.approvers?.length) {
      // approvers are optional for npl_evaluate — only used by ApprovalPolicy
    }
    if (policy.priority === undefined || policy.priority < 0 || policy.priority > 999) {
      errors.push(`policies[${i}].priority: must be 0-999`);
    }
    if (policy.when.verb && !VALID_VERBS.has(policy.when.verb)) {
      errors.push(`policies[${i}].when.verb: invalid verb "${policy.when.verb}"`);
    }
    for (const label of policy.when.labels ?? []) {
      if (!LABEL_PATTERN.test(label)) {
        errors.push(`policies[${i}].when.labels: invalid label "${label}"`);
      }
    }
  }

  return errors;
}

// --- Profile Resolution ---

/**
 * Find the security-profiles directory. Searches relative to this file
 * and relative to CWD.
 */
function findProfilesDir(): string {
  const candidates = [
    resolve(process.cwd(), "security-profiles"),
    resolve(process.cwd(), "..", "security-profiles"),
    // Relative to the source file location
    resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..", "security-profiles"),
  ];
  for (const dir of candidates) {
    if (existsSync(dir)) return dir;
  }
  throw new Error(
    `Cannot find security-profiles directory. Searched:\n  ${candidates.join("\n  ")}`
  );
}

/**
 * Resolve a community profile name to its YAML content.
 * @openclaw/security-gmail → security-profiles/gmail.yaml
 */
export function resolveProfile(profileName: string): CommunityProfile {
  const match = profileName.match(PROFILE_PATTERN);
  if (!match) {
    throw new Error(`Invalid profile name: ${profileName} (expected @org/security-<service>)`);
  }

  const service = match[1];
  const profilesDir = findProfilesDir();
  const profilePath = resolve(profilesDir, `${service}.yaml`);

  if (!existsSync(profilePath)) {
    throw new Error(`Profile not found: ${profilePath} (for ${profileName})`);
  }

  const content = readFileSync(profilePath, "utf-8");
  return parseYaml(content) as CommunityProfile;
}

// --- Tenant Variable Interpolation ---

/**
 * Recursively interpolate {var} placeholders with tenant values.
 */
export function interpolateTenantVars(obj: unknown, vars: Record<string, string>): unknown {
  if (typeof obj === "string") {
    let result = obj;
    for (const [key, value] of Object.entries(vars)) {
      result = result.replaceAll(`{${key}}`, value);
    }
    return result;
  }
  if (Array.isArray(obj)) {
    return obj.map((item) => interpolateTenantVars(item, vars));
  }
  if (obj && typeof obj === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
      result[key] = interpolateTenantVars(value, vars);
    }
    return result;
  }
  return obj;
}

// --- Merge ---

/**
 * Merge community profiles with operator overrides into a single policy.
 *
 * Merging strategy:
 * - Profile tool definitions are loaded first
 * - Operator tool_overrides take precedence over profile definitions
 * - Classifiers from profiles are included; operator classifiers override per-tool
 * - Policies come from the deployment config only (not from profiles)
 */
export function mergeProfiles(config: SecurityPolicyConfig): MergedSecurityPolicy {
  const tenantVars = config.tenant ?? {};
  const toolOverrides = config.tool_overrides ?? {};
  const configClassifiers = config.classifiers ?? {};

  const mergedTools: Record<string, Record<string, MergedToolAnnotation>> = {};
  const mergedClassifiers: Record<string, Record<string, ClassifierRule[]>> = {};

  // Step 1: Load profiles
  for (const profileName of config.profiles ?? []) {
    const profile = resolveProfile(profileName);
    const service = profile.service;

    if (!mergedTools[service]) mergedTools[service] = {};
    if (!mergedClassifiers[service]) mergedClassifiers[service] = {};

    for (const [toolName, toolDef] of Object.entries(profile.tools ?? {})) {
      const annotations: Record<string, boolean> = {};
      for (const hint of VALID_HINTS) {
        if (hint in toolDef) {
          annotations[hint] = (toolDef as Record<string, unknown>)[hint] as boolean;
        }
      }

      mergedTools[service][toolName] = {
        annotations,
        verb: toolDef.verb ?? null,
        labels: toolDef.labels ?? [],
      };

      if (toolDef.classify) {
        mergedClassifiers[service][toolName] = toolDef.classify;
      }
    }
  }

  // Step 2: Apply operator tool_overrides (precedence over profiles)
  for (const [service, tools] of Object.entries(toolOverrides)) {
    if (!mergedTools[service]) mergedTools[service] = {};
    for (const [toolName, overrides] of Object.entries(tools)) {
      if (!mergedTools[service][toolName]) {
        mergedTools[service][toolName] = { annotations: {}, verb: null, labels: [] };
      }
      const existing = mergedTools[service][toolName];
      for (const hint of VALID_HINTS) {
        if (hint in overrides) {
          existing.annotations[hint] = (overrides as Record<string, unknown>)[hint] as boolean;
        }
      }
      if (overrides.verb) existing.verb = overrides.verb;
      if (overrides.labels) existing.labels = overrides.labels;
    }
  }

  // Step 3: Apply operator classifiers (override per service.tool)
  for (const [service, tools] of Object.entries(configClassifiers)) {
    if (!mergedClassifiers[service]) mergedClassifiers[service] = {};
    for (const [toolName, rules] of Object.entries(tools)) {
      mergedClassifiers[service][toolName] = rules;
    }
  }

  // Step 4: Interpolate tenant variables into classifiers
  const interpolated = interpolateTenantVars(mergedClassifiers, tenantVars) as typeof mergedClassifiers;

  return {
    version: config.version,
    tool_annotations: mergedTools,
    classifiers: interpolated,
    policies: config.policies ?? [],
  };
}

// --- Load and Apply ---

/**
 * Load a security policy YAML file and return the raw config.
 */
export function loadSecurityPolicyFile(filePath: string): SecurityPolicyConfig {
  const absPath = resolve(filePath);
  if (!existsSync(absPath)) {
    throw new Error(`File not found: ${absPath}`);
  }
  const content = readFileSync(absPath, "utf-8");
  const config = parseYaml(content) as SecurityPolicyConfig;
  if (!config || typeof config !== "object") {
    throw new Error("Invalid YAML: expected an object");
  }
  return config;
}

/**
 * Full pipeline: load → validate → merge → serialize.
 * Returns the JSON string ready to push to PolicyStore.
 */
export function processSecurityPolicy(filePath: string): {
  merged: MergedSecurityPolicy;
  json: string;
  stats: {
    toolAnnotations: number;
    classifierTools: number;
    policyRules: number;
    bytes: number;
  };
} {
  const config = loadSecurityPolicyFile(filePath);

  const errors = validateConfig(config);
  if (errors.length > 0) {
    throw new Error(`Validation failed:\n  ${errors.join("\n  ")}`);
  }

  const merged = mergeProfiles(config);
  const json = JSON.stringify(merged);

  return {
    merged,
    json,
    stats: {
      toolAnnotations: Object.values(merged.tool_annotations).reduce(
        (sum, tools) => sum + Object.keys(tools).length,
        0
      ),
      classifierTools: Object.values(merged.classifiers).reduce(
        (sum, tools) => sum + Object.keys(tools).length,
        0
      ),
      policyRules: merged.policies.length,
      bytes: json.length,
    },
  };
}
