"""Infrastructure configuration and deployment scripts test suite.

Uses Python standard library only (no external dependencies such as pyyaml)
to validate Docker Compose, Nginx, Prometheus alerts, Grafana dashboards,
and shell scripts for syntax, security posture, and production readiness.
"""

import json
import os
import re
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent


class InfraConfigTests(unittest.TestCase):

    def test_json_files_are_valid(self):
        """All Grafana dashboard and provisioning JSON files must parse valid JSON."""
        json_files = list(REPO_ROOT.glob("infra/**/*.json"))
        self.assertGreater(len(json_files), 0, "No JSON files found in infra/")
        for path in json_files:
            with self.subTest(file=str(path.relative_to(REPO_ROOT))):
                content = path.read_text(encoding="utf-8")
                try:
                    data = json.loads(content)
                    self.assertIsInstance(data, (dict, list))
                except json.JSONDecodeError as exc:
                    self.fail(f"Invalid JSON in {path}: {exc}")

    def test_shell_scripts_syntax(self):
        """All shell scripts under infra/ must pass bash -n syntax check."""
        sh_files = list(REPO_ROOT.glob("infra/**/*.sh"))
        self.assertGreater(len(sh_files), 0, "No shell scripts found in infra/")
        for path in sh_files:
            with self.subTest(script=str(path.relative_to(REPO_ROOT))):
                res = subprocess.run(
                    ["bash", "-n", str(path)],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    res.returncode,
                    0,
                    f"Syntax error in {path}:\n{res.stderr}",
                )

    def test_shell_scripts_are_executable(self):
        """All shell scripts in infra/ must have executable permissions."""
        sh_files = list(REPO_ROOT.glob("infra/**/*.sh"))
        for path in sh_files:
            with self.subTest(script=str(path.relative_to(REPO_ROOT))):
                self.assertTrue(
                    os.access(path, os.X_OK),
                    f"Script {path} is not executable (chmod +x required)",
                )

    def test_compose_prod_defines_required_services(self):
        """compose.prod.yaml must define all 9 production services."""
        compose_path = REPO_ROOT / "compose.prod.yaml"
        self.assertTrue(compose_path.exists(), "compose.prod.yaml not found")
        content = compose_path.read_text(encoding="utf-8")

        expected_services = [
            "nginx",
            "backend",
            "frontend",
            "postgres",
            "redis",
            "prometheus",
            "grafana",
            "loki",
            "alloy",
        ]
        for service in expected_services:
            with self.subTest(service=service):
                pattern = rf"(?m)^  {service}:"
                self.assertTrue(
                    re.search(pattern, content),
                    f"Service '{service}' not declared at top-level under services: in compose.prod.yaml",
                )

    def test_compose_prod_security_hygiene(self):
        """compose.prod.yaml must not expose internal databases or metrics to public ports."""
        compose_path = REPO_ROOT / "compose.prod.yaml"
        content = compose_path.read_text(encoding="utf-8")

        # Public exposed ports should only be 80 and 443 on nginx
        # Internal ports (5432, 6379, 9090, 3000, 3100) must bind to 127.0.0.1 if published
        forbidden_public_ports = [
            r'"5432:5432"',
            r'"6379:6379"',
            r'"9090:9090"',
            r'"3000:3000"',
            r'"3100:3100"',
        ]
        for port_pat in forbidden_public_ports:
            self.assertIsNone(
                re.search(port_pat, content),
                f"Found potentially publicly exposed internal port pattern: {port_pat}",
            )

    def test_prometheus_alert_rules_defined(self):
        """Prometheus alerts.yml must contain required operational alerts."""
        alerts_path = REPO_ROOT / "infra" / "monitoring" / "prometheus" / "alerts.yml"
        self.assertTrue(alerts_path.exists(), "alerts.yml not found")
        content = alerts_path.read_text(encoding="utf-8")

        expected_alerts = [
            "DevSphereBackendDown",
            "DevSphereHigh5xxRate",
            "DevSphereHighLatencyP95",
            "DevSphereSolverSlow",
            "DevSphereQuartzJobFailures",
            "DevSphereOutboxBacklog",
            "DevSphereWebhookFailureSpike",
            "DevSphereBackupOverdue",
            "DevSphereJvmHeapPressure",
        ]
        for alert_name in expected_alerts:
            with self.subTest(alert=alert_name):
                pattern = rf"alert:\s+{alert_name}"
                self.assertTrue(
                    re.search(pattern, content),
                    f"Alert rule '{alert_name}' missing from alerts.yml",
                )

    def test_nginx_security_headers_and_restrictions(self):
        """Nginx configuration must enforce security headers and protect internal endpoints."""
        sec_headers_path = REPO_ROOT / "infra" / "nginx" / "snippets" / "security-headers.conf"
        self.assertTrue(sec_headers_path.exists(), "security-headers.conf not found")
        sec_content = sec_headers_path.read_text(encoding="utf-8")

        self.assertIn("X-Frame-Options", sec_content)
        self.assertIn("X-Content-Type-Options", sec_content)
        self.assertIn("Content-Security-Policy", sec_content)

        devsphere_conf = REPO_ROOT / "infra" / "nginx" / "conf.d" / "devsphere.conf"
        self.assertTrue(devsphere_conf.exists(), "devsphere.conf not found")
        conf_content = devsphere_conf.read_text(encoding="utf-8")
        self.assertIn("/actuator", conf_content)

    def test_no_hardcoded_cloud_secrets(self):
        """Infra configs and scripts must not contain hardcoded private keys or access tokens."""
        dangerous_patterns = [
            re.compile(r"-----BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY-----"),
            re.compile(r"AKIA[0-9A-Z]{16}"),
        ]
        for path in REPO_ROOT.glob("infra/**/*"):
            if path.is_file():
                try:
                    text = path.read_text(encoding="utf-8", errors="ignore")
                except Exception:
                    continue
                for pat in dangerous_patterns:
                    match = pat.search(text)
                    self.assertIsNone(
                        match,
                        f"Found potential secret matching {pat.pattern} in {path}",
                    )


if __name__ == "__main__":
    unittest.main()
