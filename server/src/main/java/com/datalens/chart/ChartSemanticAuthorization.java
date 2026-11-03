package com.datalens.chart;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.semantic.SemanticService;
import com.datalens.semantic.SemanticRules;
import com.datalens.model.ModelOutputValidator;
import static com.datalens.chart.ChartModels.*;
/** Semantic capability survives preview, persistence, sharing and refresh. No provider dependency. */
@Component
public class ChartSemanticAuthorization {
 private final SemanticService semantics;
 public ChartSemanticAuthorization(SemanticService semantics){this.semantics=semantics;}
 public void validate(CurrentActor actor,Definition d){
  var b=d.semanticBinding();if(b==null)return;
  var p=semantics.retrievePublished(actor,d.subjectId());
  if(p.version()!=b.publicationVersion()||!p.digest().equals(b.digest())||!p.scopeFingerprint().equals(b.scopeFingerprint()))throw denied();
  var metric=ModelOutputValidator.eligible(p).stream().filter(m->m.id().equals(b.metricId())).findFirst().orElseThrow(ChartSemanticAuthorization::denied);
  if(!d.sql().equals(metric.sql())||!d.parameters().equals(metric.parameters())||!d.metricDescription().equals(description(metric)))throw denied();
  var none=new TimeRule("NONE",null,null,null,null);
  if(!Objects.equals(d.timeRule(),none)&&!Objects.equals(d.timeRule(),SemanticRules.lastMonthRule(metric)))throw denied();
 }
 public static String description(com.datalens.semantic.SemanticModels.Metric m){String text=m.name()+"；口径："+m.calculation()+"；过滤："+m.filters()+"；粒度："+m.grain()+"；空值："+m.nullHandling()+"；时间："+m.timePolicy()+"；单位："+m.unit();return text.length()>4000?text.substring(0,3990)+"（说明已截短）":text;}
 private static AccessDeniedException denied(){return new AccessDeniedException("Semantic publication binding changed");}
}
