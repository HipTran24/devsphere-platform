# DevSphere Security Policy

## Reporting Security Issues

The DevSphere engineering team takes security vulnerabilities seriously. If you discover a vulnerability, please report it privately:

- **Email**: security@devsphere.internal (or open a confidential advisory)
- **Encryption**: Include your public PGP key if you require encrypted communications.
- **Scope**: Includes backend authentication/authorization, audit log integrity, outbox dispatching, AWS S3 presigned URL generation, and container configurations.

Please do not disclose security issues in public GitHub issues or forum threads until a patched release has been verified and deployed.

## Security Practices in DevSphere
1. **SSRF & Metadata Protection**: AWS S3 pre-signed URL generation strictly rejects loopback, AWS IMDS (169.254.169.254), and private IP addresses.
2. **Formula Injection Defense**: CSV exports of audit records neutralize formulas beginning with `=`, `+`, `-`, or `@`.
3. **Database Encryption**: Nightly backups are encrypted via OpenSSL using AES-256-CBC and PBKDF2 with 600,000 iterations.
4. **Least Privilege**: Containers execute under unprivileged user IDs (UID 10001). Nginx terminates TLS and restricts management endpoints (`/actuator/**`).
