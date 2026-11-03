# M04 frontend verification

Scope: private saved charts, immutable preview-then-publish revisions, owner list/detail, fresh authorized execution on detail entry. No AI, sharing, export, or remote publication.

Implemented:
- TABLE, KPI, BAR, LINE and PIE through whitelisted ECharts mappings. Existing exact ECharts 6.1.0 dependency is unchanged.
- String-preserving TABLE/KPI, original-value tooltips and exact data view; plotting alone uses approximate finite Numbers. No client aggregation. Rejects invalid references, duplicate output names, invalid numeric types, nonzero underflow, truncated pies/KPI and negative pies.
- Explicit save after successful preview only. Editing invalidates preview. Relative date settings use explicit business timezone and zero-based DATE parameter indices.
- Entry refresh, current task status, sanitized failure, labeled last successful refresh timestamp. Access errors clear private data; stale request abort/generation guards stop polling on navigation. Concurrent revision changes invalidate a just-fetched result.
- Stable idempotency keys for preview/refresh retry; server-owned save/revision identity and version check.

Checks:
- `npm run build`: passed (typecheck and production build); pre-existing large chunk warning remains, chart route lazy-loaded and ECharts modules restricted to used charts/components.
- `npm test`: 36 passed, 6 test files.
- Playwright suite: attempted, but no browser test reached execution in this sandbox. Bundled executable is absent; installed `/usr/bin/chromium` failed local IPC socket creation (`process_singleton_posix.cc`, `Operation not permitted`), including a permitted escalated retry. Must run in CI/supported browser environment.
- 11 chart Playwright scenarios cover explicit save, immutable edit, refresh failure, permission clearing, stale polling/navigation, KPI precision, BAR/LINE/PIE renderer initialization, server schema rejection and idempotent retry. Existing foundation navigation fixture now expects the implemented chart list.

Mock UI contracts and unit tests do not establish real StarRocks behavior or production readiness. Backend integration and CI acceptance remain separate.
