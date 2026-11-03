# M07 frontend verification

## Task and boundary

M07 admin model configuration UI depends on M01 authentication and the M07 server configuration contract. Scope is the `/model` administrator route, navigation, typed configuration API, explicit credentials handling, and tests. Natural-language conversations, executing provider requests, production configuration and deployment are not included. No new dependency or schema change is introduced by this frontend work.

The UI uses GET/PUT `/api/v1/admin/model` with the existing response envelope and CSRF client. PUT carries `expectedVersion` and nullable `apiKey`; response-only version/credential status and unknown properties are not echoed. Leaving the key blank preserves it; changing endpoint requires a fresh key. Model endpoints must be HTTPS with no URL credentials, query or fragment; the backend remains authoritative for outbound allowlisting. Configuration supports timeout 1–60 seconds, input 256–24000 characters, output 128–4096 tokens, repair count 0–1 and daily budget 1024–10000000 tokens.

Keys are password inputs and are cleared after validation failures, submission, discard, reload and unmount. They are never stored in local/session storage. Responses are projected into a credential-free form. Saves are single-flight and never automatically retried; failed or uncertain saves require an explicit reload to inspect server state before another mutation. Failed reload removes stale configuration. The administrator route and navigation guard are defense in depth, not substitutes for server authorization.

## Checks executed in this workspace

- With Node v22.23.4 (`PATH=/tmp/datalens-diagnosis-tools/node_modules/node/bin:$PATH`), `cd web && npm ci --offline --cache /tmp/datalens-npm-cache`: passed, 108 packages installed from the committed lockfile. An initial default-Node24 attempt failed engine validation; no dependency versions were changed.
- `cd web && npm test` after clean Node22 install: passed, 8 files / 47 tests, including 6 new model API/validation cases.
- `cd web && npm run build` after clean Node22 install: passed, including `vue-tsc --noEmit`; existing large-chunk warning remains.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e -- e2e/model.spec.ts`: blocked before test execution by Chromium local IPC `socket() failed: Operation not permitted`. One approved escalated attempt reached the same system restriction. No browser assertions or visual QA passed, and no screenshot is claimed.

`npx playwright test e2e/model.spec.ts --list` passed and discovered all six tests. Six browser cases are supplied: ordinary-user route denial; admin credential discard, version and CSRF; endpoint replacement and conflict recovery; expired session; unconfigured defaults/navigation cleanup; failed reload removing stale state. The final two were added after identifying the common browser startup blocker and were not executed.

No real API key was used, no model provider was contacted and no publish/deploy was performed. CI on the exact final commit, runnable-browser verification and live server/provider integration remain separate gates.

## CI acceptance update

Exact commit `9b793a7f5d48de1a9fc7e4174890381aada6c04e`: all 47 unit tests, 62 Chromium scenarios (including six model scenarios), clean install and production build passed in [Frontend verification](https://github.com/NJgaoyang/datalens/actions/runs/41654779915). This supersedes the earlier pending-browser status; local IPC restriction remains an environment limitation.
