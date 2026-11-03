package com.datalens.query;

import java.util.*;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class QueryModels {
    private QueryModels() {}
    public record Parameter(@NotNull @Pattern(regexp="STRING|DECIMAL|INTEGER|BOOLEAN|DATE|TIMESTAMP|NULL") String type,
                            @Size(max=4000) String value) {}
    public record Submit(@NotBlank @Size(max=36) String subjectId,@NotBlank @Size(max=16000) String sql,
                         @NotNull @Size(max=100) List<@NotNull @Valid Parameter> parameters,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey) {
        @Override public String toString(){return "Submit[REDACTED]";}
    }
    public record TaskView(String id,String subjectId,String status,String errorCode,Instant createdAt,
                           Instant finishedAt,Long durationMs,boolean truncated) {}
    public record Column(String name,String jdbcType) {}
    /** Values are strings/null to preserve DECIMAL/BIGINT precision across JavaScript clients. */
    public record Result(List<Column> columns,List<List<String>> rows,boolean truncated,int rowLimit) {}
}
