# M07 — Model integration and controlled calls

## Verified CI acceptance — 2026-11-03

Exact accepted code commit: `9b793a7f5d48de1a9fc7e4174890381aada6c04e`.

- [Backend verification](https://github.com/NJgaoyang/datalens/actions/runs/41654779930): Maven verify/package succeeded, **288 unit + 73 real MySQL/Redis integration tests passed**, including all four ModelIT cases.
- [Frontend verification](https://github.com/NJgaoyang/datalens/actions/runs/41654779915): clean install, **47 unit + 62 Chromium browser tests passed**, typecheck/build passed.
- [Disposable Docker acceptance](https://github.com/NJgaoyang/datalens/actions/runs/41654779970): model-free cold start, same-volume restart, dependency failure/recovery passed.
- CI exposed and fixed a MySQL budget-row lock-upgrade deadlock: duplicate-key no-op UPDATE now obtains an exclusive row lock before reservation. Concurrent budget test passed. Foundation stage/module/seed-count assertions were updated for M07.
- **No live model provider was contacted.** Real-provider TLS/protocol/billing and real StarRocks/business-semantic acceptance remain pending. No merge/deployment.

The local-verification limitations below describe the earlier local environment; the CI results above supersede their pending CI/browser/MySQL/Compose entries.

## Task and scope

Depends on M03 query service and M06 published semantic snapshots. Goal: provider-neutral ModelGateway, structured QueryIntent/QueryPlan/ChartSpec proposals, superadmin configuration, encrypted secrets, bounded calls and safe application-owned tools. M06 code/CI accepted upstream; real business semantic examples and StarRocks acceptance remain separate pending gates.

Non-goals: M08 conversations and chart-save workflow, arbitrary model-generated SQL or rewritten metric semantics, hosted deployment, real provider credential setup, external business-data transmission, financial billing guarantees.

## Implementation

- Additive V8 model_configuration, model_daily_budget and model_call tables; token reservations are atomic, conservative and durable. Calls store only actor/subject/version/digest/status/usage reservation, never prompts or responses.
- Independent AES-GCM model wrapping key, endpoint-bound AAD, random nonce; no key needed at bean construction/startup. New endpoint discards old encrypted key. Admin read reveals only credentialConfigured.
- SUPER_ADMIN configuration route and UI with optimistic version checks, CSRF/session authorization, no secret browser persistence, bounded fields and safe uncertain-save recovery.
- ModelGateway with explicit OpenAI-compatible HTTP adapter. Spring AI 2.0.2 BeanOutputConverter supplies JSON Schema using strict Jackson; original output is independently parsed and semantically validated.
- Exact public numeric IPv4 HTTPS allowlist, standard trusted IP-SAN certificate verification, no DNS resolution, redirects or proxies. Whole-body deadline, 128 KiB transport response cap, 64K-character structured output cap, two calls/process, zero queue, maximum one repair.
- Only PUBLIC declared metric fields/time fields are eligible. Prompt catalog is IDs/names/units/approved result labels, not SQL/source mappings/rows/credentials/full publications. No automatic SDK tools. M07 supports one unchanged reviewed metric; unsupported modifications require clarification.
- Application-owned ModelQueryTools reconstructs exact reviewed SQL/parameters and submits through M03 with publication-bound QueryPurposeAuthorization. Ownership, active account, source, fields and publication are rechecked at execution and result delivery. Query/Chart services never depend on model availability.
- Safe error codes; no upstream error body copied to frontend/logs. Rechecks after blocking persistence/audit prevent delivery after revocation. Secret validation matches transport restrictions.

## Executed checks (2026-11-03)

- All main and test Java sources compiled using Java 21.0.13 and cached resolved dependencies, including Spring AI 2.0.2 model/commons and schema generation dependencies.
- JUnit Platform run of every `com.datalens.*Test`: **288 started, 288 passed, 0 failed**, including 83 new M07 tests. Explicit test javaagent avoids unavailable sandbox attach sockets. Independent reviewer reran all 288 successfully.
- Actual Spring AI JSON Schema generation exercised during gateway mock tests. No provider requests performed.
- Node 22.23.4 clean `npm ci --offline --no-audit --no-fund` using existing cache passed; frontend **47 tests passed**, typecheck and production build passed. Build has pre-existing large bundle warnings.
- Four new ModelIT scenarios compile: default-disabled startup, admin/CSRF restrictions, encrypted credential round-trip/version conflict, concurrent budget reservation. These require real MySQL/Redis and have not run locally.
- Six new browser scenarios discover; local Chromium cannot start due sandbox IPC restriction even after approved escalation. No visual/browser assertion success claimed.
- Independent security review found and verified fixes for final-delivery revocation timing and credential-validation mismatch; no remaining high/critical finding in reviewed scope.
- Reconciled baseline files against remote commit ffe49c0971ea8cba22f8d07b233754fbb7246d91, preserving M06 integration test fix and acceptance records.

## Not run / remaining gates

- Standard full Maven lifecycle locally: failed before compilation because parent POM cache/proxy setup is unavailable. Manual cached Java compile/JUnit run is evidence, not a full Maven/package result.
- Exact M07 commit CI, MySQL/Redis integration and empty/same-volume Compose restart: pending publication/CI. No local Docker/MySQL/Redis acceptance claimed.
- Browser tests: pending CI with functional Chromium sandbox.
- Real provider: no key installed, provider contacted, business data transmitted, billing incurred or provider TLS/protocol behavior verified. Requires explicit authorized secure setup and synthetic bounded smoke test.
- Current endpoint policy deliberately excludes ordinary hostname endpoints. An approved trusted public IP-SAN gateway is needed; do not weaken TLS/SSRF controls to bypass it.
- Token limits reserve prompt UTF-8 bytes plus output cap and repair budget without refund. They are not currency-price guarantees or verified provider token metering. Process-level concurrency is not a multi-replica global concurrency quota; daily budget remains MySQL-global.
- ChartSpec is a proposal only; M04 actual-result validation remains mandatory. Real semantic correctness and real StarRocks acceptance are outstanding. User elected to defer StarRocks live setup until development is complete.

## Changed files

Model package (12 production files), security routes, V8 migration, Spring AI model dependency; model test fixtures/unit/integration tests; frontend model API/view/navigation/tests; model API contract, deployment environment/docs, architecture/requirements/README and verification notes. No existing migration edited. No merge or production deployment.
