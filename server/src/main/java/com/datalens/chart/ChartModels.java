package com.datalens.chart;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.datalens.query.QueryModels.Parameter;

public final class ChartModels {
    private ChartModels() {}
    public record Spec(@NotNull @Pattern(regexp="TABLE|KPI|BAR|LINE|PIE") String type,
        @Size(max=128) String dimension,@NotNull @Size(max=20) List<@NotBlank @Size(max=128) String> measures) {}
    public record TimeRule(@NotNull @Pattern(regexp="NONE|LAST_DAYS") String kind,Integer days,String timeZone,Integer startIndex,Integer endIndex) {}
    public record Definition(@NotBlank @Size(max=36) String subjectId,@NotBlank @Size(max=16000) String sql,
        @NotNull @Size(max=100) List<@NotNull @Valid Parameter> parameters,@NotNull @Valid TimeRule timeRule,
        @NotNull @Size(max=4000) String metricDescription,@NotNull @Valid Spec spec) {}
    public record PreviewInput(@NotNull @Valid Definition definition,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey) {}
    public record Save(@NotBlank @Size(max=160) String name,@NotBlank String previewId) {}
    public record Publish(@NotBlank @Size(max=160) String name,@NotBlank String previewId,@Min(1) long expectedVersion) {}
    public record Refresh(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey) {}
    public record Preview(String id,Definition definition,String taskId) {}
    public record Summary(String id,String name,long ownerId,long version,String revisionId,Instant updatedAt) {}
    public record Detail(String id,String name,long ownerId,long version,String revisionId,Definition definition,String latestTaskId,Instant lastSuccessAt) {}
}
