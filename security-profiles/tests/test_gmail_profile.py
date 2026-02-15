"""
Tests for the Gmail community security profile.

Validates that:
1. The profile YAML is well-formed and complete
2. Every tool has required fields (annotations, verb)
3. Classifiers use valid template variables
4. The Acme Corp example policies produce correct allow/deny/approve decisions

Run with: python -m pytest security-profiles/tests/test_gmail_profile.py -v
"""

import os
from pathlib import Path

import yaml
import pytest

PROFILES_DIR = Path(__file__).parent.parent
GMAIL_PROFILE = PROFILES_DIR / "gmail.yaml"
ACME_EXAMPLE = PROFILES_DIR / "examples" / "acme-corp.yaml"

# Valid values for the vocabulary
VALID_VERBS = {"get", "list", "create", "update", "delete"}
VALID_LABEL_NAMESPACES = {"scope", "env", "category", "data"}
MCP_ANNOTATIONS = {"readOnlyHint", "destructiveHint", "openWorldHint", "idempotentHint"}
VALID_ACTIONS = {"allow", "deny", "require_approval"}


@pytest.fixture
def gmail_profile():
    with open(GMAIL_PROFILE) as f:
        return yaml.safe_load(f)


@pytest.fixture
def acme_config():
    with open(ACME_EXAMPLE) as f:
        return yaml.safe_load(f)


# ── Profile structure tests ────────────────────────────────────────────────


class TestGmailProfileStructure:
    def test_has_required_fields(self, gmail_profile):
        assert "service" in gmail_profile
        assert "description" in gmail_profile
        assert "tools" in gmail_profile
        assert gmail_profile["service"] == "gmail"

    def test_all_tools_have_annotations(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            for annotation in MCP_ANNOTATIONS:
                assert annotation in tool, (
                    f"Tool '{tool_name}' missing annotation '{annotation}'"
                )
                assert isinstance(tool[annotation], bool), (
                    f"Tool '{tool_name}' annotation '{annotation}' must be bool"
                )

    def test_all_tools_have_valid_verb(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            assert "verb" in tool, f"Tool '{tool_name}' missing verb"
            assert tool["verb"] in VALID_VERBS, (
                f"Tool '{tool_name}' has invalid verb '{tool['verb']}'"
            )

    def test_labels_have_valid_namespace(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            for label in tool.get("labels", []):
                ns = label.split(":")[0]
                assert ns in VALID_LABEL_NAMESPACES, (
                    f"Tool '{tool_name}' has label '{label}' with invalid namespace '{ns}'"
                )

    def test_classifiers_reference_valid_fields(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            for classifier in tool.get("classify", []):
                assert "field" in classifier or "present" in classifier, (
                    f"Tool '{tool_name}' classifier must have 'field'"
                )
                assert "set_labels" in classifier, (
                    f"Tool '{tool_name}' classifier must have 'set_labels'"
                )
                for label in classifier["set_labels"]:
                    ns = label.split(":")[0]
                    assert ns in VALID_LABEL_NAMESPACES, (
                        f"Classifier label '{label}' has invalid namespace"
                    )

    def test_classifiers_use_template_variables(self, gmail_profile):
        """Classifiers should use {company_domain} not hardcoded domains."""
        for tool_name, tool in gmail_profile["tools"].items():
            for classifier in tool.get("classify", []):
                for key in ("contains", "not_contains"):
                    val = classifier.get(key, "")
                    if "@" in val:
                        assert "{company_domain}" in val, (
                            f"Tool '{tool_name}' classifier hardcodes a domain "
                            f"instead of using {{company_domain}} template"
                        )


# ── Tool classification consistency ────────────────────────────────────────


class TestGmailToolClassification:
    def test_readonly_tools_are_not_destructive(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            if tool["readOnlyHint"]:
                assert not tool["destructiveHint"], (
                    f"Tool '{tool_name}' is readOnly but also destructive"
                )

    def test_destructive_tools_are_not_readonly(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            if tool["destructiveHint"]:
                assert not tool["readOnlyHint"], (
                    f"Tool '{tool_name}' is destructive but also readOnly"
                )

    def test_destructive_tools_use_delete_verb(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            if tool["destructiveHint"]:
                assert tool["verb"] == "delete", (
                    f"Tool '{tool_name}' is destructive but verb is '{tool['verb']}', "
                    f"expected 'delete'"
                )

    def test_readonly_tools_use_get_or_list_verb(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            if tool["readOnlyHint"]:
                assert tool["verb"] in ("get", "list"), (
                    f"Tool '{tool_name}' is readOnly but verb is '{tool['verb']}', "
                    f"expected 'get' or 'list'"
                )

    def test_send_email_is_openworld(self, gmail_profile):
        """send_email sends data outside the system — must be openWorldHint."""
        tool = gmail_profile["tools"]["send_email"]
        assert tool["openWorldHint"] is True

    def test_draft_email_is_not_openworld(self, gmail_profile):
        """draft_email doesn't send — must NOT be openWorldHint."""
        tool = gmail_profile["tools"]["draft_email"]
        assert tool["openWorldHint"] is False

    def test_send_email_has_internal_external_classifiers(self, gmail_profile):
        """send_email must classify internal vs external recipients."""
        tool = gmail_profile["tools"]["send_email"]
        classifiers = tool.get("classify", [])
        fields_and_ops = [(c.get("field"), list(set(c.keys()) - {"field", "set_labels"})) for c in classifiers]

        has_internal = any(
            c.get("field") == "to" and "contains" in c and "internal" in str(c["set_labels"])
            for c in classifiers
        )
        has_external = any(
            c.get("field") == "to" and "not_contains" in c and "external" in str(c["set_labels"])
            for c in classifiers
        )
        assert has_internal, "send_email must classify internal recipients"
        assert has_external, "send_email must classify external recipients"


# ── Coverage tests ─────────────────────────────────────────────────────────


class TestGmailToolCoverage:
    """Ensure the profile covers the core Gmail MCP tools."""

    CORE_TOOLS = [
        "send_email",
        "draft_email",
        "read_email",
        "search_emails",
        "modify_email",
        "delete_email",
        "batch_delete_emails",
    ]

    def test_core_tools_covered(self, gmail_profile):
        tool_names = set(gmail_profile["tools"].keys())
        for tool in self.CORE_TOOLS:
            assert tool in tool_names, f"Core tool '{tool}' not covered in profile"

    def test_no_unknown_verbs(self, gmail_profile):
        for tool_name, tool in gmail_profile["tools"].items():
            assert tool["verb"] in VALID_VERBS


# ── Policy evaluation simulation ───────────────────────────────────────────


class TestAcmePolicyEvaluation:
    """
    Simulate policy evaluation for the Acme Corp example config.
    Tests that the Gmail profile + Acme policies produce correct decisions.
    """

    def _resolve_labels(self, gmail_profile, tool_name, arguments, tenant):
        """Resolve tool labels + classifier labels for given arguments."""
        tool = gmail_profile["tools"].get(tool_name, {})
        labels = set(tool.get("labels", []))

        for classifier in tool.get("classify", []):
            field = classifier.get("field")
            value = arguments.get(field, "")

            if "contains" in classifier:
                pattern = classifier["contains"].replace(
                    "{company_domain}", tenant["company_domain"]
                )
                if isinstance(value, list):
                    if any(pattern in v for v in value):
                        labels.update(classifier["set_labels"])
                elif pattern in str(value):
                    labels.update(classifier["set_labels"])

            if "not_contains" in classifier:
                pattern = classifier["not_contains"].replace(
                    "{company_domain}", tenant["company_domain"]
                )
                if isinstance(value, list):
                    if any(pattern not in v for v in value):
                        labels.update(classifier["set_labels"])
                elif pattern not in str(value):
                    labels.update(classifier["set_labels"])

            if "present" in classifier and classifier["present"]:
                if field in arguments and arguments[field]:
                    labels.update(classifier["set_labels"])

        return labels

    def _evaluate_policies(self, policies, tool_annotations, verb, labels):
        """Evaluate policies in priority order. First match wins."""
        sorted_policies = sorted(policies, key=lambda p: p.get("priority", 999))

        for policy in sorted_policies:
            when = policy.get("when", {})
            match_mode = policy.get("match", "all")

            conditions_met = []

            # Check annotation conditions
            for annotation in MCP_ANNOTATIONS:
                if annotation in when:
                    conditions_met.append(
                        tool_annotations.get(annotation) == when[annotation]
                    )

            # Check verb condition
            if "verb" in when:
                conditions_met.append(verb == when["verb"])

            # Check label conditions
            if "labels" in when:
                for required_label in when["labels"]:
                    conditions_met.append(required_label in labels)

            # Empty when = always matches
            if not conditions_met:
                return policy["action"], policy["name"]

            if match_mode == "all" and all(conditions_met):
                return policy["action"], policy["name"]
            elif match_mode == "any" and any(conditions_met):
                return policy["action"], policy["name"]

        return "deny", "no matching policy"

    def _decide(self, gmail_profile, acme_config, tool_name, arguments=None):
        """Full decision pipeline: profile → classify → evaluate."""
        arguments = arguments or {}
        tool = gmail_profile["tools"].get(tool_name, {})
        tenant = acme_config["tenant"]

        annotations = {a: tool.get(a) for a in MCP_ANNOTATIONS}
        verb = tool.get("verb")
        labels = self._resolve_labels(gmail_profile, tool_name, arguments, tenant)

        return self._evaluate_policies(
            acme_config["policies"], annotations, verb, labels
        )

    # ── Scenario tests ─────────────────────────────────────────────────

    def test_read_email_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(gmail_profile, acme_config, "read_email")
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_search_emails_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(gmail_profile, acme_config, "search_emails")
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_list_labels_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(gmail_profile, acme_config, "list_email_labels")
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_internal_email_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "send_email",
            {"to": ["colleague@acme.com"], "subject": "Meeting", "body": "Hi"},
        )
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_external_email_requires_approval(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "send_email",
            {"to": ["vendor@external.com"], "subject": "Quote", "body": "Hi"},
        )
        assert action == "require_approval", (
            f"Expected require_approval, got {action} from '{rule}'"
        )

    def test_bcc_email_denied(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "send_email",
            {"to": ["colleague@acme.com"], "bcc": ["spy@external.com"], "subject": "X", "body": "Y"},
        )
        assert action == "deny", f"Expected deny, got {action} from '{rule}'"

    def test_delete_email_requires_approval(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "delete_email",
            {"messageId": "msg-123"},
        )
        assert action == "require_approval", (
            f"Expected require_approval, got {action} from '{rule}'"
        )

    def test_batch_delete_requires_approval(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "batch_delete_emails",
            {"messageIds": ["msg-1", "msg-2"]},
        )
        assert action == "require_approval", (
            f"Expected require_approval, got {action} from '{rule}'"
        )

    def test_draft_internal_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "draft_email",
            {"to": ["colleague@acme.com"], "subject": "Draft", "body": "Hi"},
        )
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_draft_external_allowed(self, gmail_profile, acme_config):
        """Drafts to external are allowed — they're not sent yet."""
        action, rule = self._decide(
            gmail_profile, acme_config, "draft_email",
            {"to": ["vendor@external.com"], "subject": "Draft", "body": "Hi"},
        )
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"

    def test_create_filter_denied_by_default(self, gmail_profile, acme_config):
        """Acme uses default-deny — creating filters is not explicitly allowed."""
        action, rule = self._decide(
            gmail_profile, acme_config, "create_filter",
        )
        assert action == "deny", f"Expected deny, got {action} from '{rule}'"

    def test_modify_email_denied_by_default(self, gmail_profile, acme_config):
        """Modifying emails (label changes) is not explicitly allowed in Acme config."""
        action, rule = self._decide(
            gmail_profile, acme_config, "modify_email",
        )
        assert action == "deny", f"Expected deny, got {action} from '{rule}'"

    def test_download_attachment_allowed(self, gmail_profile, acme_config):
        action, rule = self._decide(
            gmail_profile, acme_config, "download_attachment",
            {"messageId": "msg-1", "attachmentId": "att-1"},
        )
        assert action == "allow", f"Expected allow, got {action} from '{rule}'"
