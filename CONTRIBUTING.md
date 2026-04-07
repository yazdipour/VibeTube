# Contributing

Thanks for considering a contribution.

## Ground Rules

- Keep changes scoped to `SmartTubeWeb`.
- Do not commit secrets, OAuth tokens, API keys, generated sessions, or local `.env` files.
- Prefer small, focused pull requests with a clear summary and verification steps.
- Keep user-facing behavior documented when adding or changing features.

## Local Setup

1. Copy `.env.example` to `.env`.
2. Fill in the required YouTube OAuth and InnerTube settings.
3. Run:

   ```sh
   docker compose up -d --build
   ```

## Checks

Run the checks relevant to your change:

```sh
node --check frontend/app.js
node --check frontend/sw.js
python3 -m py_compile yt-dlp-server/server.py
docker run --rm -v "$PWD/backend:/app" -w /app gradle:8.10.2-jdk17 gradle test --no-daemon
```

## Pull Requests

Include:

- What changed
- Why it changed
- How it was tested
- Any setup or migration notes

## Reporting Security Issues

Do not open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).
