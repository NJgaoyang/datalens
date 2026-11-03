package com.datalens.model;

/** Provider protocol boundary. Implementations must never log credentials or prompts. */
public interface ModelTransport {
    String complete(String endpoint, String modelId, String apiKey, String prompt,
                    int maxOutputTokens, int timeoutSeconds);
}
