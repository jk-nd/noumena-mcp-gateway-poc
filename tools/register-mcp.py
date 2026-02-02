#!/usr/bin/env python3
"""
MCP Service Registration Tool

Setup:
    cd tools
    python3 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt

Registers a new MCP service by:
1. Adding to docker-compose.yml
2. Adding to upstreams.yaml
3. Generating NPL governance template
4. Showing next steps
"""

import os
import sys
import yaml
from pathlib import Path

# Colors for terminal output
class Colors:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    END = '\033[0m'
    BOLD = '\033[1m'

def print_header(text):
    print(f"\n{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.BLUE}{text.center(60)}{Colors.END}")
    print(f"{Colors.BOLD}{Colors.BLUE}{'='*60}{Colors.END}\n")

def print_step(num, text):
    print(f"{Colors.GREEN}[{num}]{Colors.END} {text}")

def print_warn(text):
    print(f"{Colors.YELLOW}WARNING:{Colors.END} {text}")

def print_error(text):
    print(f"{Colors.RED}ERROR:{Colors.END} {text}")

def prompt(text, default=None):
    if default:
        result = input(f"{Colors.BOLD}{text}{Colors.END} [{default}]: ").strip()
        return result if result else default
    return input(f"{Colors.BOLD}{text}{Colors.END}: ").strip()

def confirm(text):
    result = input(f"{Colors.BOLD}{text}{Colors.END} [y/N]: ").strip().lower()
    return result == 'y'

def get_project_root():
    """Find the project root (where docker-compose.yml lives)"""
    current = Path(__file__).parent.parent
    if (current / "deployments" / "docker-compose.yml").exists():
        return current
    return None

def update_docker_compose(root, service_name, docker_image, env_vars):
    """Add new service to docker-compose.yml"""
    compose_path = root / "deployments" / "docker-compose.yml"
    
    with open(compose_path, 'r') as f:
        compose = yaml.safe_load(f)
    
    # Create service entry
    service_entry = {
        'image': docker_image,
        'networks': ['mcp-network']
    }
    
    if env_vars:
        service_entry['environment'] = [f"{k}={v}" for k, v in env_vars.items()]
    
    # Add service
    compose['services'][f"{service_name}-mcp"] = service_entry
    
    with open(compose_path, 'w') as f:
        yaml.dump(compose, f, default_flow_style=False, sort_keys=False)
    
    return True

def update_upstreams(root, service_name, service_id):
    """Add new service to upstreams.yaml"""
    upstreams_path = root / "configs" / "upstreams.yaml"
    
    with open(upstreams_path, 'r') as f:
        upstreams = yaml.safe_load(f) or {'upstreams': {}}
    
    upstreams['upstreams'][service_id] = {
        'type': 'docker_mcp',
        'endpoint': f"http://{service_name}-mcp:8080",
        'vault_path': f"secret/data/tenants/{{tenant}}/users/{{user}}/{service_name}"
    }
    
    with open(upstreams_path, 'w') as f:
        yaml.dump(upstreams, f, default_flow_style=False)
    
    return True

def generate_npl_template(root, service_name, service_id, operations):
    """Generate NPL governance template"""
    npl_dir = root / "npl" / "governance"
    npl_dir.mkdir(parents=True, exist_ok=True)
    
    # Convert service name to PascalCase for protocol name
    protocol_name = ''.join(word.capitalize() for word in service_name.replace('-', '_').split('_'))
    
    operations_npl = ""
    for op in operations:
        op_name = ''.join(word.capitalize() for word in op.replace('-', '_').split('_'))
        operations_npl += f'''
    /**
     * Check if {op} operation is allowed.
     * @param metadata Operation metadata for policy decisions
     * @return true if allowed, false if needs approval
     */
    @api
    permission[user] can{op_name}(metadata: Map<Text, Text>) returns Boolean | active {{
        // Add constraints here:
        // - Budget checks
        // - Rate limits
        // - Time restrictions
        return true;
    }};
'''
    
    npl_content = f'''package governance.services

/**
 * Governance constraints for {service_name} service.
 * 
 * This protocol defines what operations an agent can perform
 * and under what conditions.
 */
@api
protocol[user] {protocol_name}Governance(
    var dailyLimit: Number,
    var requireApprovalAbove: Number
) {{
    initial state active;
    final state suspended;
    
    private var usageToday: Number = 0;
    private var lastResetDate: LocalDate = now().toLocalDate();
    
    /**
     * Reset daily counters if needed.
     */
    private function resetIfNewDay() -> {{
        if (now().toLocalDate().isAfter(lastResetDate, false)) {{
            usageToday = 0;
            lastResetDate = now().toLocalDate();
        }};
    }};
{operations_npl}
    /**
     * Suspend this service for the user.
     */
    @api
    permission[user] suspend() | active {{
        become suspended;
    }};
    
    /**
     * Reactivate this service.
     */
    @api
    permission[user] reactivate() | suspended {{
        become active;
    }};
}}
'''
    
    npl_path = npl_dir / f"{service_name}_governance.npl"
    with open(npl_path, 'w') as f:
        f.write(npl_content)
    
    return npl_path

def main():
    print_header("MCP Service Registration")
    
    root = get_project_root()
    if not root:
        print_error("Could not find project root. Run from project directory.")
        sys.exit(1)
    
    print(f"Project root: {root}\n")
    
    # Step 1: Get service details
    print_step(1, "Service Details")
    print()
    
    service_name = prompt("Service name (e.g., google-flights)", "").lower().replace(' ', '-')
    if not service_name:
        print_error("Service name is required")
        sys.exit(1)
    
    service_id = prompt("Service ID for tools (e.g., google_flights)", 
                        service_name.replace('-', '_'))
    
    # Check if using mock or real
    use_mock = confirm("Use mock MCP server for testing?")
    
    if use_mock:
        docker_image = None  # Will use local mock-mcp build
    else:
        docker_image = prompt("Docker image (e.g., mcp/google-flights:latest)")
    
    # Step 2: Operations
    print()
    print_step(2, "Define Operations")
    print("Enter operation names (one per line, empty to finish):")
    print("Example: search, book, cancel")
    print()
    
    operations = []
    while True:
        op = input("  Operation: ").strip().lower().replace(' ', '_')
        if not op:
            break
        operations.append(op)
    
    if not operations:
        operations = ["default"]
    
    # Step 3: Environment variables (for real images)
    env_vars = {}
    if not use_mock:
        print()
        print_step(3, "Environment Variables")
        print("Enter environment variables (empty name to finish):")
        print()
        
        while True:
            var_name = input("  Variable name: ").strip()
            if not var_name:
                break
            var_value = input(f"  {var_name} value: ").strip()
            env_vars[var_name] = var_value
    
    # Summary
    print()
    print_header("Summary")
    print(f"  Service Name:  {service_name}")
    print(f"  Service ID:    {service_id}")
    print(f"  Docker Image:  {'mock-mcp (local)' if use_mock else docker_image}")
    print(f"  Operations:    {', '.join(operations)}")
    if env_vars:
        print(f"  Env Vars:      {', '.join(env_vars.keys())}")
    print()
    
    if not confirm("Proceed with registration?"):
        print("Cancelled.")
        sys.exit(0)
    
    # Execute
    print()
    print_header("Registering Service")
    
    # Update docker-compose
    print_step(1, "Updating docker-compose.yml...")
    if use_mock:
        # For mock, we build from local mock-mcp
        compose_path = root / "deployments" / "docker-compose.yml"
        with open(compose_path, 'r') as f:
            compose = yaml.safe_load(f)
        
        compose['services'][f"{service_name}-mcp"] = {
            'build': {
                'context': '../mock-mcp',
                'dockerfile': 'Dockerfile'
            },
            'networks': ['mcp-network']
        }
        
        with open(compose_path, 'w') as f:
            yaml.dump(compose, f, default_flow_style=False, sort_keys=False)
    else:
        update_docker_compose(root, service_name, docker_image, env_vars)
    print(f"    {Colors.GREEN}Done{Colors.END}")
    
    # Update upstreams
    print_step(2, "Updating upstreams.yaml...")
    update_upstreams(root, service_name, service_id)
    print(f"    {Colors.GREEN}Done{Colors.END}")
    
    # Generate NPL
    print_step(3, "Generating NPL governance template...")
    npl_path = generate_npl_template(root, service_name, service_id, operations)
    print(f"    {Colors.GREEN}Done{Colors.END} -> {npl_path.relative_to(root)}")
    
    # Next steps
    print()
    print_header("Next Steps")
    
    print(f"""
1. {Colors.BOLD}Start the new service:{Colors.END}
   cd deployments
   docker compose up -d {service_name}-mcp

2. {Colors.BOLD}Add credentials to Vault:{Colors.END}
   docker exec -it deployments-vault-1 vault kv put \\
     secret/tenants/demo-tenant/users/user@example.com/{service_name} \\
     access_token="your-token-here"

3. {Colors.BOLD}Update Gateway tools list:{Colors.END}
   Edit gateway/src/.../McpServer.kt and add:
   {', '.join([f'"{service_id}.{op}"' for op in operations])}

4. {Colors.BOLD}Customize NPL governance:{Colors.END}
   Edit {npl_path.relative_to(root)}

5. {Colors.BOLD}Rebuild and restart:{Colors.END}
   docker compose up -d --build gateway executor
""")

if __name__ == "__main__":
    main()
