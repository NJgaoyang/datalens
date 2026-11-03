package com.datalens.chart;
import java.time.Instant;import java.util.*;
import org.junit.jupiter.api.Test;import static org.mockito.Mockito.*;import static org.assertj.core.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;import com.datalens.permission.PermissionService;import com.datalens.identity.CurrentActor;import com.datalens.query.QueryService;import static com.datalens.chart.ChartModels.*;
class ChartSemanticListTest {
 @Test void revokedSemanticTitlesAndCountsAreFilteredWithoutExecutingSql(){var store=mock(ChartStore.class);var queries=mock(QueryService.class);var permissions=mock(PermissionService.class);var policy=mock(ChartPermissionService.class);var json=JsonMapper.builder().build();var actor=new CurrentActor(1,"reader","USER",1);when(permissions.current()).thenReturn(actor);var service=new ChartService(store,queries,permissions,json,policy);
  var stale=new Summary("stale","Secret stale title",2,1,"r1",Instant.EPOCH,"ALL",false);var visible=new Summary("visible","Public chart",2,1,"r2",Instant.EPOCH,"ALL",false);when(store.list(actor,"all",1,200)).thenReturn(new com.datalens.common.api.PageResult<>(List.of(stale,visible),2,1,200));var d=new Definition("subject","SELECT amount FROM orders",List.of(),new TimeRule("NONE",null,null,null,null),"metric",new Spec("TABLE",null,List.of()),new SemanticBinding(1,"a".repeat(64),"scope","metric"));when(store.definition("stale","r1")).thenReturn(json.writeValueAsString(d));when(store.definition("visible","r2")).thenReturn(json.writeValueAsString(d));when(policy.executionOwner(actor,"stale","r1")).thenThrow(new org.springframework.security.access.AccessDeniedException("withdrawn"));when(policy.executionOwner(actor,"visible","r2")).thenReturn(actor);when(policy.view(actor,"visible")).thenReturn(visible);
  var result=service.list("all",1,20);assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(visible);verifyNoInteractions(queries);
 }
}
