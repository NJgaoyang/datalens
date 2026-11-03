# M06 frontend verification

Scope: authorized subject semantic retrieval and administrator draft, review, publication, withdrawal and version history. The editor uses the exact backend DTOs with bounded JSON and a field/schema guide; no generated business facts or example-query execution are included.

Implemented safeguards:
- Ordinary users only request current publications and alias/name/question matches. Administrator draft/history reads are separate; semantic reads never use chart sharing as authority.
- Human review requires a checked attestation for the saved unchanged draft. Editing disables publication, saving invalidates review, and every mutation sends the current expected version. Duplicate clicks are suppressed. Network uncertainty and version conflicts require reloading server state, not blind mutation retries.
- Subject changes, navigation and session changes abort requests and clear content. Published content and search results must agree on the release version. Denied access clears previously visible content.
- Administrators list disabled-source subjects and independently load safe draft metadata. Redacted version-only drafts remain withdrawable when source metadata is disabled or effective field scope is empty; no inaccessible document is retained. Withdrawal requires explicit confirmation.
- The UI identifies relation fanout risks, explicitly states cross-table/JOIN publication is unsupported, and does not present schema fixtures or structural validation as real business correctness.

Executed on final frontend source:
- `npm run build`: passed Vue/TypeScript checking and Vite production build; existing large-chunk warning remains.
- `npm test`: 41 tests passed in 7 files, including 4 semantic API tests.
- `npm run test:e2e -- --list`: 56 scenarios discovered in 6 files, including 13 new semantic scenarios.
- `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e -- e2e/semantics.spec.ts --workers=1`: attempted before the final three recovery scenarios were added. All 10 attempted scenarios stopped at Chromium launch before assertions: `process_singleton_posix.cc` socket creation denied (`Operation not permitted`). No local browser pass or screenshot verification claimed. Run all 56 scenarios in CI on the exact published commit.

No dependency or lockfile changes. Local Node is v24.22.1 rather than the project's supported Node 22 range; the exact-version CI remains the canonical environment. No real StarRocks, model, business-ground-truth, backend integration or database migration checks were performed by this frontend subtask. Mock UI tests do not establish live integration correctness.

Initial commit 22a59cc9fe53be047b1103cc5705f9747fdce1a8 passed the complete frontend workflow, including 56 Chromium scenarios: https://github.com/NJgaoyang/datalens/actions/runs/41649868323 . A backend test-isolation correction triggers another exact-commit full workflow before final acceptance.
