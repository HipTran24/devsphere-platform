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


    def test_dockerfiles_security_and_non_root(self):
        """Dockerfiles must enforce multi-stage builds, non-root user UID 10001, and healthchecks."""
        backend_df = REPO_ROOT / "Dockerfile"
        self.assertTrue(backend_df.exists(), "Backend Dockerfile not found")
        backend_content = backend_df.read_text(encoding="utf-8")

        self.assertIn("FROM eclipse-temurin:27-jdk", backend_content)
        self.assertIn("FROM eclipse-temurin:27-jre", backend_content)
        self.assertIn("10001", backend_content)
        self.assertIn("USER devsphere:devsphere", backend_content)
        self.assertIn("tini", backend_content)
        self.assertIn("HEALTHCHECK", backend_content)
        self.assertIn("8081/actuator/health/readiness", backend_content)

        frontend_df = REPO_ROOT / "frontend" / "Dockerfile"
        self.assertTrue(frontend_df.exists(), "Frontend Dockerfile not found")
        frontend_content = frontend_df.read_text(encoding="utf-8")

        self.assertIn("FROM node:22-alpine", frontend_content)
        self.assertIn("10001", frontend_content)
        self.assertIn("USER nextjs", frontend_content)
        self.assertIn("HEALTHCHECK", frontend_content)
        self.assertIn("3000/login", frontend_content)

    def test_disaster_recovery_backup_pipeline(self):
        """Backup and restore scripts must enforce AES-256 PBKDF2 600k and integrity invariants."""
        common_path = REPO_ROOT / "infra" / "backup" / "backup-common.sh"
        self.assertTrue(common_path.exists())
        common_content = common_path.read_text(encoding="utf-8")

        self.assertIn("-aes-256-cbc", common_content)
        self.assertIn("-pbkdf2", common_content)
        self.assertIn("600000", common_content)

        backup_path = REPO_ROOT / "infra" / "backup" / "backup-postgres.sh"
        self.assertTrue(backup_path.exists())
        backup_content = backup_path.read_text(encoding="utf-8")
        self.assertIn("sha256_of", backup_content)
        self.assertIn(".meta.json", backup_content)

        restore_path = REPO_ROOT / "infra" / "backup" / "restore-verify.sh"
        self.assertTrue(restore_path.exists())
        restore_content = restore_path.read_text(encoding="utf-8")
        self.assertIn("DROP DATABASE IF EXISTS", restore_content)
        self.assertIn("-lt 36", restore_content)
        self.assertIn("10:t", restore_content)

    def test_observability_pipeline_and_log_shipping(self):
        """Prometheus, Loki, and Alloy configurations must define valid observability pipelines."""
        prom_path = REPO_ROOT / "infra" / "monitoring" / "prometheus" / "prometheus.yml"
        self.assertTrue(prom_path.exists())
        prom_content = prom_path.read_text(encoding="utf-8")
        self.assertIn("backend:8081", prom_content)
        self.assertIn("/actuator/prometheus", prom_content)
        self.assertIn("admin-token", prom_content)

        loki_path = REPO_ROOT / "infra" / "monitoring" / "loki" / "loki-config.yml"
        self.assertTrue(loki_path.exists())
        loki_content = loki_path.read_text(encoding="utf-8")
        self.assertIn("3100", loki_content)
        self.assertIn("retention_enabled: true", loki_content)

        alloy_path = REPO_ROOT / "infra" / "monitoring" / "alloy" / "config.alloy"
        self.assertTrue(alloy_path.exists())
        alloy_content = alloy_path.read_text(encoding="utf-8")
        self.assertIn("/var/lib/docker/containers/*/*-json.log", alloy_content)
        self.assertIn("http://loki:3100/loki/api/v1/push", alloy_content)

    def test_ci_pipeline_structure_and_gates(self):
        """CI pipeline must enforce concurrency controls, timeouts, and all required quality gates."""
        ci_path = REPO_ROOT / ".github" / "workflows" / "ci.yml"
        self.assertTrue(ci_path.exists())
        ci_content = ci_path.read_text(encoding="utf-8")

        self.assertIn("concurrency:", ci_content)
        self.assertIn("cancel-in-progress:", ci_content)
        self.assertIn("backend-verify:", ci_content)
        self.assertIn("frontend-check:", ci_content)
        self.assertIn("frontend-e2e:", ci_content)
        self.assertIn("infra-and-ai-verify:", ci_content)
        self.assertIn("timeout-minutes:", ci_content)
        self.assertIn("java-version: '27'", ci_content)
        self.assertIn("./mvnw -B verify", ci_content)
        self.assertIn("npm run test:e2e", ci_content)
        self.assertIn("tests/infra", ci_content)


if __name__ == "__main__":
    unittest.main()
