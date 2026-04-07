# Security Policy

## Reporting Vulnerabilities

Please do not report security vulnerabilities in public issues.

If this repository is hosted on GitHub, use GitHub private vulnerability reporting when available. Otherwise, contact the maintainers privately through the repository owner profile.

Include:

- A clear description of the issue
- Steps to reproduce
- Affected configuration or deployment mode
- Any known mitigations

## Sensitive Data

Do not commit:

- `.env`
- OAuth client secrets
- access or refresh tokens
- `youtube-session.json`
- Docker volumes or runtime data
- private API keys

## Deployment Guidance

This app is intended for self-hosting. If exposed outside a trusted local network, put it behind access control, TLS, and appropriate network restrictions.
