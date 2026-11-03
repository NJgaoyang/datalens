package com.datalens.chart;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.permission.PermissionService;
import com.datalens.identity.CurrentActor;
import com.datalens.query.QueryService;
import static com.datalens.query.QueryModels.*;
import static com.datalens.chart.ChartModels.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ChartServiceTest {
    ChartStore store;QueryService queries;PermissionService permissions;ChartService service;
    final CurrentActor actor=new CurrentActor(7,"owner","USER",1);
    final Definition definition=new Definition("subject","SELECT amount FROM orders",List.of(),new TimeRule("NONE",null,null,null,null),"实付金额",new Spec("KPI",null,List.of("amount")));
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    @BeforeEach void setup(){store=mock(ChartStore.class);queries=mock(QueryService.class);permissions=mock(PermissionService.class);when(permissions.current()).thenReturn(actor);when(permissions.requireActive(actor)).thenReturn(actor);service=new ChartService(store,queries,permissions,json);}
    ChartStore.PreviewRow preview(long owner){return new ChartStore.PreviewRow("preview",owner,1,json.writeValueAsString(definition),"[]","task",null);}
    Summary chart(long owner){return new Summary("chart","Chart",owner,1,"rev",Instant.now());}
    @Test void oversizedParametersFailBeforeDatabasePersistence(){var values=new ArrayList<Parameter>();for(int i=0;i<20;i++)values.add(new Parameter("STRING","x".repeat(4000)));var d=new Definition("s","SELECT amount FROM orders",values,definition.timeRule(),"",definition.spec());assertThatThrownBy(()->service.preview(new PreviewInput(d,"preview-key"),"r")).hasMessageContaining("大小");verifyNoInteractions(store,queries);}
    @Test void otherUserCannotReadSaveOrPublishPreview(){when(store.preview("preview")).thenReturn(preview(8));assertThatThrownBy(()->service.preview("preview")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.save(new Save("Chart","preview"),"r")).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(queries);}
    @Test void saveRequiresSuccessfulAuthorizedPreview(){when(store.preview("preview")).thenReturn(preview(7));when(queries.result("task")).thenThrow(new AccessDeniedException("revoked"));assertThatThrownBy(()->service.save(new Save("Chart","preview"),"r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).save(any(),any(),anyString(),anyString());}
    @Test void saveChecksActualSchemaRatherThanClientClaims(){when(store.preview("preview")).thenReturn(preview(7));when(queries.result("task")).thenReturn(new Result(List.of(new Column("changed","DECIMAL")),List.of(),false,1000));assertThatThrownBy(()->service.save(new Save("Chart","preview"),"r")).hasMessageContaining("字段");verify(store,never()).save(any(),any(),anyString(),anyString());}
    @Test void successfulSaveUsesPersistedDefinitionAndActingIdentity(){when(store.preview("preview")).thenReturn(preview(7));when(queries.result("task")).thenReturn(new Result(List.of(new Column("amount","DECIMAL")),List.of(List.of("9007199254740993.1200")),false,1000));service.save(new Save("Chart","preview"),"request");verify(store).save(eq(actor),eq(preview(7)),eq("Chart"),eq("request"));}
    @Test void privateMetadataAndRefreshDoNotAllowAdminOrOtherOwnerImpersonation(){when(store.chart("chart")).thenReturn(chart(8));assertThatThrownBy(()->service.detail("chart")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.refresh("chart",new Refresh("refresh-key"),"r")).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(queries);}
    @Test void purposeGuardRechecksOwnerAndPreviewAuthVersion(){var guard=new ChartPurposeAuthorization(store,permissions);when(store.chart("chart")).thenReturn(chart(8));assertThatThrownBy(()->guard.authorize(actor,new QueryService.QueryContext("CHART","chart","rev"))).isInstanceOf(AccessDeniedException.class);when(store.preview("preview")).thenReturn(new ChartStore.PreviewRow("preview",7,2,"{}","[]","task",null));assertThatThrownBy(()->guard.authorize(actor,new QueryService.QueryContext("CHART_PREVIEW","preview",null))).isInstanceOf(AccessDeniedException.class);}
}
