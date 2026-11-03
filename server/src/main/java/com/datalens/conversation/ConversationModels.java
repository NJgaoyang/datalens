package com.datalens.conversation;
import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
import com.datalens.chart.ChartModels.*;
public final class ConversationModels {
 private ConversationModels(){}
 public record Create(@NotBlank @Size(max=36) String subjectId,@NotBlank @Size(max=160) String title,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey){}
 public record Send(@NotBlank @Size(max=4000) String question,@Min(0) long expectedVersion,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey){}
 public record Save(@NotBlank @Size(max=160) String name,@AssertTrue boolean confirmation){}
 public record Summary(String id,String subjectId,String title,long version){}
 public record Detail(String id,String subjectId,String title,long version,List<Turn> turns){}
 public record Turn(String id,long sequence,String question,String status,String clarification,String metricDescription,String previewId,String taskId,String chartId,String errorCode,Instant createdAt,Spec spec,TimeRule timeRule,Instant queryTime,Boolean truncated){}
}
