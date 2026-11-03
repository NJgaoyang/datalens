package com.datalens.export;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.datalens.chart.*;
import com.datalens.identity.*;
import com.datalens.permission.PermissionService;
import com.datalens.audit.AuditService;
import static com.datalens.export.ExportModels.*;
class ExportServiceTest {
 ExportStore store;ChartService charts;ChartPermissionService policy;PermissionService permissions;AccountDirectory accounts;ExportService service;
 CurrentActor actor=new CurrentActor(7,"viewer","USER",1);
 ChartModels.Summary c=new ChartModels.Summary("chart","Title",8,3,"rev",Instant.EPOCH,"SPECIFIC",false);
 ExportStore.Row row(){return new ExportStore.Row("job",7,1,"chart",3,"rev",4,"task","SUCCEEDED",null,Instant.now().plusSeconds(900));}
 @BeforeEach void setup(){store=mock(ExportStore.class);charts=mock(ChartService.class);policy=mock(ChartPermissionService.class);permissions=mock(PermissionService.class);accounts=mock(AccountDirectory.class);service=new ExportService(store,charts,policy,permissions,accounts,mock(AuditService.class));when(permissions.current()).thenReturn(actor);when(permissions.requireActive(actor)).thenReturn(actor);when(policy.view(actor,"chart")).thenReturn(c);when(store.grant("chart",7,3)).thenReturn(4L);when(store.get("job")).thenReturn(row());}
 @AfterEach void close(){service.close();}
 @Test void viewShareAndOwnershipNeverImplyExport(){when(store.grant("chart",7,3)).thenReturn(0L);assertThat(service.permission("chart").allowed()).isFalse();assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).content(anyString());}
 @Test void crossUserAuthVersionAndExpiredJobsDenied(){assertThatThrownBy(()->service.check(new CurrentActor(9,"other","USER",1),row())).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.check(new CurrentActor(7,"viewer","USER",2),row())).isInstanceOf(AccessDeniedException.class);var expired=new ExportStore.Row("job",7,1,"chart",3,"rev",4,"task","SUCCEEDED",null,Instant.EPOCH);assertThatThrownBy(()->service.check(actor,expired)).hasMessageContaining("过期");}
 @Test void revokeAndRegrantCannotResurrectDownload(){when(store.grant("chart",7,3)).thenReturn(5L);assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).content(anyString());}
 @Test void shareRevokeAndRepublishInvalidateOldDownload(){when(policy.view(actor,"chart")).thenThrow(new AccessDeniedException("revoked"));assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);doReturn(new ChartModels.Summary("chart","Title",8,4,"newrev",Instant.EPOCH,"SPECIFIC",false)).when(policy).view(actor,"chart");when(store.grant("chart",7,4)).thenReturn(4L);assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);}
 @Test void rechecksAfterReadingFileAndCurrentSourcePolicy(){when(store.content("job")).thenAnswer(inv->{when(store.grant("chart",7,3)).thenReturn(0L);return new byte[]{1};});assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);verify(policy,atLeast(2)).executionOwner(actor,"chart","rev");}
 @Test void sourcePolicyRevocationDeniesBeforeFileRead(){when(policy.executionOwner(actor,"chart","rev")).thenThrow(new AccessDeniedException("source revoked"));assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).content(anyString());}
 @Test void sourceRevisionChangeDeniesOldJobAndDownload(){doThrow(new AccessDeniedException("Query authorization changed")).when(charts).authorizeResultTask("chart","task");assertThatThrownBy(()->service.get("job")).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.download("job","r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).content(anyString());}
 @Test void nonAdminCannotManageGrants(){when(permissions.requireSuperAdmin()).thenThrow(new AccessDeniedException("admin required"));assertThatThrownBy(()->service.grant("chart",new Grant(7,3,true),"r")).isInstanceOf(AccessDeniedException.class);verify(store,never()).grant(any(),anyString(),any(),any(),anyString());}
}
