# Independent business and performance acceptance

The empty template deliberately fails. Unit fixtures are NOT 100 reviewed business cases.
Do not commit actual business data, personal data, secrets or live results to this public repository.

1. Business owners provide at least 100 unique questions and independently verified expected results; include aggregation, dates/timezones, JOIN/grain, nulls, distinct, units and ambiguity. Include intentional rejections and clarifications. Unsupported JOINs must be reported as unsupported, never hidden by reducing the suite.
2. Store the authorized dataset snapshot/version and questions privately. Each case has `id`, `question`, `reviewed_by`, `categories` (array) and `expected` (JSON). Use decimal/bigint strings. An expected object can encode rows, refusal or clarification. Row ordering is exact; normalize ordering independently and consistently when order is not meaningful. A shared wrong calculation in expected and actual is not independent verification.
3. Human review freezes the suite with `frozen_at`, `reviewer`, `dataset_version`; only then agree `target_correctness` in (0,1]. Hash the entire canonical JSON with SHA-256 using `evaluate_business.canonical`. Record any refreeze as a new suite and do not silently change expected results after seeing model output.
4. Under separately authorized live testing, capture actual output for each case. Result JSON has `suite_sha256`, `dataset_version`, `commit_sha`, `executed_at`, and `cases: [{"id": "...", "actual": ...}]`.
5. Run `python3 tests/acceptance/evaluate_business.py /private/frozen-suite.json /private/results.json`. Exit 0 means supplied evidence reaches its agreed target; 1 means below target; 2 means incomplete/invalid evidence. This offline comparator cannot authenticate human review or prove results came from a live execution. Preserve privately reviewed provenance alongside its value-free summary.
6. Re-run adversarial isolation and security suites regardless of correctness score. Business score never overrides a permission failure.

## Performance protocol (not yet executed)

Before load generation agree and record: commit/image digest; CPU/RAM/JVM heap; MySQL/Redis/StarRocks versions and topology; dataset rows/bytes, largest cell, cardinality/skew; chart/user counts; active users, ramp and sustained duration; query mix; export rate; model latency/budget; network; warm/cold cache; p50/p95/p99 targets, error budget and cancellation bound. Capture queue rejection rate, heap/GC, DB pool saturation, metadata growth, storage, normal-page latency while exports/large queries run, and database-side orphan queries.

Run read-only authorized load on disposable or explicitly authorized infrastructure, never production by default. Separate a real driver test from mocks. Include maximum-size cells, policy revocation during load, model outage, metadata/Redis loss and the single-instance lease. Listing currently filters all candidate charts before pagination: representative maximum asset counts are mandatory. Per-request bounds do not establish a throughput SLA. Report configuration and raw measurements privately; put only approved sanitized summaries in public artifacts. No performance target or achieved number is claimed today.
