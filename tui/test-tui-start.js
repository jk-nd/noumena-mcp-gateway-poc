#!/usr/bin/env node
// Test if TUI can start without errors
console.log("Testing TUI import...");

try {
  // Try to import the module
  console.log("Current directory:", process.cwd());
  console.log("Attempting to load ./dist/cli.js...");
  
  // This will execute the module
  import('./dist/cli.js')
    .then(() => {
      console.log("✓ TUI loaded successfully");
      setTimeout(() => process.exit(0), 1000);
    })
    .catch(err => {
      console.error("✗ Error loading TUI:");
      console.error(err);
      process.exit(1);
    });
} catch (err) {
  console.error("✗ Immediate error:");
  console.error(err);
  process.exit(1);
}

// Kill after 3 seconds no matter what
setTimeout(() => {
  console.log("Timeout - killing test");
  process.exit(0);
}, 3000);
