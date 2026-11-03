package com.datalens.semantic;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.query.QueryModels.Parameter;

public final class SemanticModels {
    private SemanticModels() {}
    public record BusinessField(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String id,
        @NotBlank @Size(max=100) String name,@NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> aliases,
        @NotNull @Valid Field physical,@NotBlank @Size(max=100) String type,@NotBlank @Size(max=50) String unit,
        @NotBlank @Pattern(regexp="PUBLIC|INTERNAL|SENSITIVE") String sensitivity) {}
    public record Metric(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String id,
        @NotBlank @Size(max=100) String name,@NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> aliases,
        @NotBlank @Size(max=4000) String calculation,@NotBlank @Size(max=2000) String filters,
        @NotBlank @Size(max=64) String timeFieldId,@NotBlank @Size(max=100) String owner,
        @NotBlank @Size(max=50) String unit,@NotBlank @Size(max=1000) String grain,
        @NotBlank @Size(max=1000) String nullHandling,@NotBlank @Size(max=1000) String timePolicy,
        @NotEmpty @Size(max=100) List<@NotBlank @Size(max=64) String> fieldIds,
        @NotBlank @Size(max=16000) String sql,@NotNull @Size(max=100) List<@NotNull @Valid Parameter> parameters) {}
    public record Dimension(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String id,
        @NotBlank @Size(max=100) String name,@NotNull @Size(max=20) List<@NotBlank @Size(max=100) String> aliases,
        @NotBlank @Size(max=64) String fieldId) {}
    public record Relation(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String id,
        @NotBlank @Size(max=100) String name,@NotBlank @Size(max=64) String leftFieldId,@NotBlank @Size(max=64) String rightFieldId,
        @NotBlank @Pattern(regexp="ONE_TO_ONE|MANY_TO_ONE|ONE_TO_MANY|MANY_TO_MANY|UNKNOWN") String cardinality,
        @NotBlank @Size(max=1000) String leftGrain,@NotBlank @Size(max=1000) String rightGrain) {}
    public record Example(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String id,
        @NotBlank @Size(max=2000) String question,@NotEmpty @Size(max=100) List<@NotBlank @Size(max=64) String> metricIds,
        @NotBlank @Size(max=16000) String sql,@NotNull @Size(max=100) List<@NotNull @Valid Parameter> parameters,
        @NotBlank @Size(max=4000) String verification) {}
    public record Document(@NotEmpty @Size(max=200) List<@NotNull @Valid BusinessField> fields,
        @NotEmpty @Size(max=100) List<@NotNull @Valid Metric> metrics,
        @NotNull @Size(max=100) List<@NotNull @Valid Dimension> dimensions,
        @NotNull @Size(max=100) List<@NotNull @Valid Relation> relations,
        @NotEmpty @Size(max=100) List<@NotNull @Valid Example> examples) {}
    public record Save(@Min(0) long expectedVersion,@NotNull @Valid Document document) {}
    public record Version(@Min(1) long expectedVersion) {}
    public record Review(@Min(1) long expectedVersion,@AssertTrue boolean confirmation) {}
    public record Draft(String subjectId,long version,Document document,boolean reviewed,Long reviewerId,Instant reviewedAt,Long publishedVersion,List<String> warnings) {}
    public record Publication(String subjectId,long version,Document document,String digest,String scopeFingerprint,long reviewerId,long publisherId,Instant publishedAt,Instant reviewedAt) {}
    public record Match(String kind,String id,String name,List<String> reasons) {}
    public record Search(long publicationVersion,List<Match> matches) {}
}
