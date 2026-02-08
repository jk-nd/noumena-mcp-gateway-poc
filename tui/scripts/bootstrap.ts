import { bootstrapNpl } from "../src/lib/api.js";

async function main() {
  try {
    console.log("🔄 Bootstrapping NPL...");
    const result = await bootstrapNpl();
    console.log("\n✅ Bootstrap complete!");
    console.log(`  Registry created: ${result.registryCreated}`);
    console.log(`  Policies created: ${result.policiesCreated}`);
    console.log(`  Enabled services: ${result.servicesEnabled.join(", ")}`);
    console.log(`  User registry created: ${result.userRegistryCreated}`);
    console.log(`  Synced users: ${result.usersCreated.join(", ")}`);
  } catch (error) {
    console.error("❌ Bootstrap failed:", error);
    process.exit(1);
  }
}

main();
