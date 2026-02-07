#!/bin/bash
echo "=== TUI Diagnostics ==="
echo ""

echo "1. Checking Node.js version:"
node --version

echo ""
echo "2. Checking if dist/ exists and is populated:"
ls -lh dist/cli.js dist/lib/api.js dist/lib/config.js 2>&1 | grep -E "dist/|total"

echo ""
echo "3. Checking node_modules:"
if [ -d "node_modules" ]; then
  echo "   ✓ node_modules exists"
  echo "   Dependencies:"
  ls node_modules/ | grep -E "^@clack|^chalk|^js-yaml" | head -5
else
  echo "   ✗ node_modules NOT FOUND - run 'npm install'"
fi

echo ""
echo "4. Testing JavaScript syntax:"
node -c dist/cli.js && echo "   ✓ Syntax OK" || echo "   ✗ Syntax Error"

echo ""
echo "5. Checking Gateway connectivity:"
curl -s -o /dev/null -w "   HTTP %{http_code}\n" http://localhost:8000/health || echo "   ✗ Cannot connect to Gateway"

echo ""
echo "6. Checking services.yaml:"
if [ -f "../configs/services.yaml" ]; then
  echo "   ✓ services.yaml exists"
  echo "   Services: $(grep -c 'name:' ../configs/services.yaml)"
else
  echo "   ✗ services.yaml NOT FOUND"
fi

echo ""
echo "7. Attempting to run TUI (will exit after 2 seconds):"
echo "   Command: node dist/cli.js"
echo ""
(sleep 2 && pkill -f "node dist/cli.js") &
node dist/cli.js 2>&1 | head -20
echo ""
echo "   (killed after 2 seconds)"

echo ""
echo "=== Diagnostic Complete ==="
