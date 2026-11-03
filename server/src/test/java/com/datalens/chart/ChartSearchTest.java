package com.datalens.chart;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.common.api.PageResult;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.query.QueryService;
import tools.jackson.databind.json.JsonMapper;
class ChartSearchTest {
 @Test void searchFiltersRevokedMetadataBeforeCountsAndNeverQueries(){var store=mock(ChartStore.class);var query=mock(QueryService.class);var permissions=mock(PermissionService.class);var policy=mock(ChartPermissionService.class);var actor=new CurrentActor(7,"viewer","USER",1);when(permissions.current()).thenReturn(actor);var visible=new ChartModels.Summary("allowed","Sales",8,1,"r1",Instant.EPOCH,"ALL",false);var hidden=new ChartModels.Summary("revoked","Sales private title",9,1,"r2",Instant.EPOCH,"ALL",false);when(store.list(actor,"all",1,200)).thenReturn(new PageResult<>(List.of(visible,hidden),2,1,200));when(policy.view(actor,"allowed")).thenReturn(visible);when(policy.executionOwner(actor,"revoked","r2")).thenThrow(new AccessDeniedException("source disabled"));var service=new ChartService(store,query,permissions,JsonMapper.builder().build(),policy);var result=service.list("all",1,20,"SaLeS");assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(visible);verifyNoInteractions(query);}
 @Test void deliveryRecheckOmitsNewlyRevokedAsset(){var store=mock(ChartStore.class);var query=mock(QueryService.class);var permissions=mock(PermissionService.class);var policy=mock(ChartPermissionService.class);var actor=new CurrentActor(7,"viewer","USER",1);when(permissions.current()).thenReturn(actor);var chart=new ChartModels.Summary("chart","Sales",8,1,"rev",Instant.EPOCH,"ALL",false);when(store.list(actor,"all",1,200)).thenReturn(new PageResult<>(List.of(chart),1,1,200));when(policy.executionOwner(actor,"chart","rev")).thenReturn(actor).thenThrow(new AccessDeniedException("revoked"));when(policy.view(actor,"chart")).thenReturn(chart);var service=new ChartService(store,query,permissions,JsonMapper.builder().build(),policy);assertThat(service.list("all",1,20,"sales").total()).isZero();verifyNoInteractions(query);}
}
