package com.datalens.model;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.datalens.chart.ChartModels.Spec;

public final class ModelModels {
    private ModelModels() {}
    public record Settings(long version,boolean enabled,String provider,String endpoint,String modelId,int timeoutSeconds,
        int maxInputChars,int maxOutputTokens,int maxRepairs,long dailyTokenBudget,boolean credentialConfigured) {}
    public record Configure(@Min(0) long expectedVersion,boolean enabled,
        @NotNull @Pattern(regexp="OPENAI_COMPATIBLE") String provider,@NotNull @Size(max=500) String endpoint,
        @NotNull @Size(max=128) String modelId,@Min(1) @Max(60) int timeoutSeconds,
        @Min(256) @Max(24000) int maxInputChars,@Min(128) @Max(4096) int maxOutputTokens,
        @Min(0) @Max(1) int maxRepairs,@Min(1024) @Max(10000000) long dailyTokenBudget,@Size(max=4096) @Pattern(regexp="[!-~]*") String apiKey) {
        @Override public String toString(){return "Configure[REDACTED]";}
    }
    public record Generate(@NotBlank @Size(max=36) String subjectId,@NotBlank @Size(max=4000) String question) {
        @Override public String toString(){return "Generate[REDACTED]";}
    }
    public record QueryIntent(@NotBlank @Size(max=2000) String summary,boolean needsClarification,
        @NotNull @Size(max=1000) String clarification,@NotNull @Size(max=10) List<@NotBlank @Size(max=64) String> metricIds,
        @NotNull @Size(max=10) List<@NotBlank @Size(max=64) String> dimensionIds) {}
    /** M07 executes exactly one reviewed metric; no model-authored SQL, filters or time rewrites. */
    public record QueryPlan(@NotNull @Size(max=64) String metricId,@Min(1) @Max(1000) int rowLimit) {}
    public record Structured(@NotNull @Valid QueryIntent intent,@NotNull @Valid QueryPlan plan,@NotNull @Valid Spec chartSpec) {}
    public record Proposal(String callId,String subjectId,long publicationVersion,String publicationDigest,Structured output) {}
    public static Settings disabled(){return new Settings(0,false,"OPENAI_COMPATIBLE","","",30,12000,2000,1,100000,false);}
}
