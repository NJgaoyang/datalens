package com.datalens.datasource;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class SourceModels {
    private SourceModels() {}
    public record SourceInput(@NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=15) String host, @Min(1) @Max(65535) int port,
        @NotBlank @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String catalog,
        @NotBlank @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String database,
        @NotBlank @Size(max=128) String username, @Size(max=1024) String password,
        boolean enabled, @NotEmpty @Size(max=100) List<@NotNull @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String> tables) {
        @Override public String toString() { return "SourceInput[REDACTED]"; }
    }
    public record SourceView(String id,String name,String host,int port,String catalog,String database,
        String username,boolean enabled,List<String> tables,long version,String harvestStatus,
        Instant lastHarvestAt,String lastErrorCode) {}
    record StoredSource(SourceView view,String credential) {
        @Override public String toString() { return "StoredSource[REDACTED]"; }
    }
    public record Field(@NotBlank @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String tableName,
        @NotBlank @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String columnName) {}
    public record ColumnView(String sourceId,String tableName,String columnName,String typeName,int ordinalPosition,boolean nullable) {}
    public record SubjectInput(@NotBlank @Size(max=100) String name,@NotBlank String sourceId,
        @NotEmpty @Size(max=1000) List<@NotNull @Valid Field> fields,
        @NotNull @Size(max=1000) List<@NotNull @Valid Field> deniedFields,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> userIds,
        @NotBlank @Pattern(regexp="NONE|AUTHORIZED_USERS|ALL_AUTHENTICATED") String shareScope) {}
    public record SubjectView(String id,String name,String sourceId,List<Field> fields,List<Field> deniedFields,
        List<Long> userIds,String shareScope) {}
}
