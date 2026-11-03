package com.datalens.export;
import java.time.Instant;
import jakarta.validation.constraints.*;
public final class ExportModels {
 private ExportModels(){}
 public record Create(@NotBlank @Size(max=36) String chartId,@NotBlank @Size(max=36) String taskId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey){}
 public record Grant(@Positive long userId,@Positive long expectedChartVersion,boolean enabled){}
 public record GrantView(long userId,boolean enabled){}
 public record Permission(boolean allowed){}
 public record Job(String id,String chartId,String taskId,String status,String errorCode,Instant expiresAt){}
}
