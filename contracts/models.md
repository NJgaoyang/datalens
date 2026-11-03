# M07 controlled model boundary

All endpoints use the standard `/api/v1` JSON envelope, session identity and CSRF policy. Keys are write-only. GET responses never contain a key or ciphertext.

- `GET /api/v1/admin/model`: SUPER_ADMIN only; returns `Settings`.
- `PUT /api/v1/admin/model`: SUPER_ADMIN + CSRF; accepts `Configure`, optimistic `expectedVersion`. New endpoint invalidates the old credential. Enabled configuration needs a new key when changing endpoint. Blank/null key preserves an existing key only at the same endpoint. Disabled configuration may remove endpoint and credential.
- `POST /api/v1/model/proposals`: authenticated + CSRF; accepts `{subjectId, question}`. Server reauthorizes the current published semantic scope before transmission and before delivery. Returns `{callId, subjectId, publicationVersion, publicationDigest, output}`. Does NOT run SQL, save/share charts, or persist conversations.

`Settings`: version, enabled, provider (`OPENAI_COMPATIBLE`), endpoint, modelId, timeoutSeconds (1–60), maxInputChars (256–24000), maxOutputTokens (128–4096), maxRepairs (0–1), dailyTokenBudget (1024–10000000), credentialConfigured. `Configure` replaces version with expectedVersion and credentialConfigured with optional apiKey (4096 printable non-space ASCII characters maximum). Endpoint max 500 and model ID max 128 characters.

`output` has exactly three records:

- `intent`: summary, needsClarification, clarification, metricIds, dimensionIds.
- `plan`: metricId, rowLimit.
- `chartSpec`: existing ChartModels.Spec (TABLE/KPI/BAR/LINE/PIE, dimension, measures).

M07 deliberately selects **one unchanged reviewed PUBLIC metric**. Executable plan rowLimit is exactly M03's 1000; no model-authored SQL, parameter/filter/date changes or dimension regrouping. Multiple metrics, altered periods, or ambiguity require clarification. Clarification output has an empty plan.metricId and TABLE/null/[] chartSpec. Generated chart mappings are proposals, not a validated chart preview: M04 must still validate actual result types, row count, truncation and numeric content before saving.

Only metrics whose declared fields and time field are PUBLIC are sent to providers. Prompt catalog contains IDs, names, units and approved result labels, not SQL, full semantic publications, source mappings, rows, credentials, owners or examples. User questions are explicitly user-supplied outbound text; operators must obtain appropriate data-transmission authorization before enabling real use. Do not enter secrets into questions. Descriptions are data, never tool instructions.

Spring AI BeanOutputConverter supplies JSON Schema using a strict Jackson mapper. The original provider text is independently decoded with duplicate/unknown/trailing/missing creator properties, scalar coercion, size/depth and referential validation. Markdown fences are rejected. One repair maximum; repair does not replay invalid model output, secrets, query results or errors. Provider rate limiting/timeouts are not retried.

`ModelQueryTools.executeReviewedMetric` is an internal, application-owned tool for M08, not a public HTTP endpoint or automatically registered SDK tool. It reconstructs exact reviewed SQL/parameters, binds publication version/digest/metric, and submits via M03. M03 revalidates publication and authorization at dispatch, persistence, status and result delivery through QueryPurposeAuthorization. No raw JDBC, client-chosen purposes, arbitrary task-ID tools, mutating tools, or recursive model tool loop exists.

Safe failures include MODEL_NOT_CONFIGURED, MODEL_CONFIG_CONFLICT, MODEL_CONFIG_CHANGED, MODEL_ENDPOINT_DENIED, MODEL_INPUT_TOO_LARGE, MODEL_NO_PUBLIC_METRICS, MODEL_BUSY, MODEL_BUDGET_EXCEEDED, MODEL_TIMEOUT, MODEL_RATE_LIMITED, MODEL_PROVIDER_FAILED, MODEL_OUTPUT_INVALID, MODEL_SEMANTIC_CHANGED. Authentication and authorization failures retain their usual 401/403 envelopes. Provider error bodies are never returned.

Daily budget is a conservative token reservation, **not a currency guarantee**: UTF-8 prompt bytes plus repair margin and requested output cap, multiplied by all possible attempts, reserved atomically in MySQL UTC day before transmission. Unused repairs, missing usage and ambiguous timeouts are not refunded. Restart or settings changes cannot reset that day's spending; stale RUNNING call rows retain their reservation and indicate an uncertain outcome. Two concurrent calls per application process, zero request queue. Query execution remains separately bounded by M03.
