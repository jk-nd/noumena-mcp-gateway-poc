# MCP Gateway Keycloak Configuration
# Simplified realm with admin, user, and agent roles

variable "default_password" {
  type = string
}

# ============================================================================
# Master Realm (imported)
# ============================================================================

import {
  to = keycloak_realm.master
  id = "master"
}

resource "keycloak_realm" "master" {
  realm        = "master"
  enabled      = true
  ssl_required = "none"
}

# ============================================================================
# MCP Gateway Realm
# ============================================================================

resource "keycloak_realm" "mcpgateway" {
  realm             = "mcpgateway"
  enabled           = true
  display_name      = "MCP Gateway"
  display_name_html = "<b>MCP Gateway</b>"

  access_code_lifespan = "30m"
  access_token_lifespan = "30m"  # Token lifetime for development
  ssl_required         = "none"
  password_policy      = "length(8)"

  internationalization {
    supported_locales = ["en"]
    default_locale    = "en"
  }
}

# User Profile configuration for custom attributes
resource "keycloak_realm_user_profile" "mcpgateway_user_profile" {
  realm_id = keycloak_realm.mcpgateway.id

  attribute {
    name         = "username"
    display_name = "$${username}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name = "length"
      config = {
        min = "3"
        max = "255"
      }
    }
    validator {
      name = "username-prohibited-characters"
    }
  }

  attribute {
    name         = "email"
    display_name = "$${email}"

    required_for_roles = ["user"]

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }

    validator {
      name = "email"
    }
  }

  attribute {
    name         = "firstName"
    display_name = "$${firstName}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name         = "lastName"
    display_name = "$${lastName}"

    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  # Custom attribute for role (admin, user, agent)
  attribute {
    name         = "role"
    display_name = "Functional Role"
    multi_valued = true

    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }
  }

  # Custom attribute for organization
  attribute {
    name         = "organization"
    display_name = "Organization"
    multi_valued = true

    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }
  }

  group {
    name                = "user-metadata"
    display_header      = "User metadata"
    display_description = "Attributes for user metadata"
  }
}

# OIDC Client for the MCP Gateway
resource "keycloak_openid_client" "mcpgateway_client" {
  realm_id                        = keycloak_realm.mcpgateway.id
  client_id                       = "mcpgateway"
  access_type                     = "PUBLIC"
  direct_access_grants_enabled    = true
  standard_flow_enabled           = true
  valid_redirect_uris             = ["*"]
  valid_post_logout_redirect_uris = ["+"]
  web_origins                     = ["*"]
}

# Protocol mapper for role claim
resource "keycloak_openid_user_attribute_protocol_mapper" "role_mapper" {
  realm_id  = keycloak_realm.mcpgateway.id
  client_id = keycloak_openid_client.mcpgateway_client.id
  name      = "role-mapper"

  user_attribute   = "role"
  claim_name       = "role"
  claim_value_type = "String"
  multivalued      = true

  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}

# Protocol mapper for organization claim
resource "keycloak_openid_user_attribute_protocol_mapper" "organization_mapper" {
  realm_id  = keycloak_realm.mcpgateway.id
  client_id = keycloak_openid_client.mcpgateway_client.id
  name      = "organization-mapper"

  user_attribute   = "organization"
  claim_name       = "organization"
  claim_value_type = "String"
  multivalued      = true

  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}

# ============================================================================
# Users
# ============================================================================

# Admin - can enable/disable services
resource "keycloak_user" "admin" {
  realm_id   = keycloak_realm.mcpgateway.id
  username   = "admin"
  email      = "admin@acme.com"
  first_name = "Admin"
  last_name  = "User"
  enabled    = true

  attributes = {
    "role"         = "admin"
    "organization" = "acme"
  }

  initial_password {
    value     = var.default_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.mcpgateway_user_profile]
}

# ============================================================================
# Realm Management — give admin user full realm-admin capabilities
# so the admin's mcpgateway-realm token can call the Keycloak Admin API
# (eliminates the need for a separate master-realm login)
# ============================================================================

data "keycloak_openid_client" "realm_management" {
  realm_id  = keycloak_realm.mcpgateway.id
  client_id = "realm-management"
}

data "keycloak_role" "realm_admin" {
  realm_id  = keycloak_realm.mcpgateway.id
  client_id = data.keycloak_openid_client.realm_management.id
  name      = "realm-admin"
}

resource "keycloak_user_roles" "admin_realm_management" {
  realm_id = keycloak_realm.mcpgateway.id
  user_id  = keycloak_user.admin.id

  role_ids = [
    data.keycloak_role.realm_admin.id
  ]
}

# ============================================================================
# System Accounts
# ============================================================================

# Gateway Service Account - Backend system service for NPL policy enforcement
# This is the service account used by the Gateway application itself
resource "keycloak_user" "gateway_service" {
  realm_id   = keycloak_realm.mcpgateway.id
  username   = "gateway"
  email      = "gateway@system.local"
  first_name = "Gateway"
  last_name  = "Service"
  enabled    = true

  attributes = {
    "role"         = "gateway"  # Gateway system service role
    "organization" = "system"
  }

  initial_password {
    value     = var.default_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.mcpgateway_user_profile]
}

# ============================================================================
# AI Agents - Default Demo Agent
# ============================================================================

# Jarvis - AI Agent (autonomous tool user)
resource "keycloak_user" "jarvis" {
  realm_id   = keycloak_realm.mcpgateway.id
  username   = "jarvis"
  email      = "jarvis@acme.com"
  first_name = "Jarvis"
  last_name  = "AI Agent"
  enabled    = true

  attributes = {
    "role"         = "agent"  # AI agent (can use tools like a human user)
    "organization" = "acme"
  }

  initial_password {
    value     = var.default_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.mcpgateway_user_profile]
}

# ============================================================================
# Default Users for Demo / Testing
# ============================================================================

# Alice - Product Manager
resource "keycloak_user" "alice" {
  realm_id   = keycloak_realm.mcpgateway.id
  username   = "alice"
  email      = "alice@acme.com"
  first_name = "Alice"
  last_name  = "Chen"
  enabled    = true

  attributes = {
    "role"         = "user"
    "organization" = "acme"
  }

  initial_password {
    value     = var.default_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.mcpgateway_user_profile]
}

# Bob - Developer
resource "keycloak_user" "bob" {
  realm_id   = keycloak_realm.mcpgateway.id
  username   = "bob"
  email      = "bob@acme.com"
  first_name = "Bob"
  last_name  = "Smith"
  enabled    = true

  attributes = {
    "role"         = "user"
    "organization" = "acme"
  }

  initial_password {
    value     = var.default_password
    temporary = false
  }

  depends_on = [keycloak_realm_user_profile.mcpgateway_user_profile]
}
