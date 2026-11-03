package com.datalens.chart;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;
import com.datalens.identity.*;
import com.datalens.permission.PermissionService;
import com.datalens.datasource.*;
import com.datalens.query.*;
import static com.datalens.chart.ChartModels.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ChartPermissionTest {
    ChartStore store;PermissionService permissions;AccountDirectory accounts;QuerySourceAccess sources;ChartPermissionService policy;ChartPurposeAuthorization guard;
    final CurrentActor a=new CurrentActor(1,"a","USER",1),b=new CurrentActor(2,"b","USER",1),admin=new CurrentActor(3,"admin","SUPER_ADMIN",1);
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    final Definition d=new Definition("subject","SELECT amount FROM orders",List.of(),new TimeRule("NONE",null,null,null,null),"amount",new Spec("TABLE",null,List.of()));
    @BeforeEach void setup(){store=mock(ChartStore.class);permissions=mock(PermissionService.class);accounts=mock(AccountDirectory.class);sources=mock(QuerySourceAccess.class);when(permissions.requireActive(any())).thenAnswer(i->i.getArgument(0));when(accounts.active(1)).thenReturn(a);
        when(store.definition("c","r")).thenReturn(json.writeValueAsString(d));when(store.shares("c")).thenReturn(List.of());
        when(sources.authorizeSharing(any(),eq("subject"),anyString(),anyList())).thenReturn(new QuerySourceAccess.Scope("source",1,List.of(new SourceModels.ColumnView("source","orders","amount","DECIMAL",1,true))));
        policy=new ChartPermissionService(store,permissions,accounts,sources,json);guard=new ChartPurposeAuthorization(store,permissions,policy,json);
    }
    void chart(String visibility,boolean deleted){when(store.chart("c")).thenReturn(new Summary("c","Chart",1,1,"r",Instant.EPOCH,visibility,deleted));}
    @Test void privateOwnerAndAdministratorOnly(){chart("PRIVATE",false);assertThat(policy.view(a,"c")).isNotNull();assertThat(policy.view(admin,"c")).isNotNull();assertThatThrownBy(()->policy.view(b,"c")).isInstanceOf(AccessDeniedException.class);}
    @Test void specificAndAllDoNotGrantManagement(){chart("SPECIFIC",false);when(store.shares("c")).thenReturn(List.of(2L));assertThat(policy.view(b,"c")).isNotNull();assertThatThrownBy(()->policy.manage(b,"c",false)).isInstanceOf(AccessDeniedException.class);when(store.shares("c")).thenReturn(List.of());assertThatThrownBy(()->policy.view(b,"c")).isInstanceOf(AccessDeniedException.class);chart("ALL",false);assertThat(policy.view(b,"c")).isNotNull();assertThatThrownBy(()->policy.manage(b,"c",false)).isInstanceOf(AccessDeniedException.class);}
    @Test void trashIsHiddenFromAllViewersButOwnerAndAdminCanRestore(){chart("ALL",true);for(var actor:List.of(a,b,admin))assertThatThrownBy(()->policy.view(actor,"c")).isInstanceOf(AccessDeniedException.class);policy.manage(a,"c",true);policy.manage(admin,"c",true);assertThatThrownBy(()->policy.manage(b,"c",true)).isInstanceOf(AccessDeniedException.class);}
    @Test void sourceOrOwnerRevocationStopsSharedTaskAndAdminCannotBypass(){chart("ALL",false);var context=new QueryService.QueryContext("CHART","c","r");assertThat(guard.executionActor(b,context)).isEqualTo(a);doThrow(new AccessDeniedException("revoked")).when(sources).authorizeSharing(any(),anyString(),anyString(),anyList());assertThatThrownBy(()->guard.authorize(b,context)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->guard.authorize(admin,context)).isInstanceOf(AccessDeniedException.class);}
    @Test void staleRevisionCannotBeReplayed(){chart("ALL",false);assertThatThrownBy(()->guard.authorize(b,new QueryService.QueryContext("CHART","c","old"))).isInstanceOf(AccessDeniedException.class);}
    @Test void sharedViewerCannotOverrideSqlOrParameters(){chart("ALL",false);when(store.run("run")).thenReturn(new ChartStore.Run("run","c","r",2,"[]",null));var context=new QueryService.QueryContext("CHART","c","r");guard.validateSubmission(b,context,new QueryModels.Submit("subject",d.sql(),List.of(),"cr_run"));assertThatThrownBy(()->guard.validateSubmission(b,context,new QueryModels.Submit("subject","SELECT secret FROM orders",List.of(),"cr_run"))).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->guard.validateSubmission(b,context,new QueryModels.Submit("subject",d.sql(),List.of(new QueryModels.Parameter("STRING","x")),"cr_run"))).isInstanceOf(AccessDeniedException.class);}
    @Test void runMustBelongToActualViewerAndChart(){chart("ALL",false);when(store.run("run")).thenReturn(new ChartStore.Run("run","c","r",1,"[]",null));assertThatThrownBy(()->guard.validateSubmission(b,new QueryService.QueryContext("CHART","c","r"),new QueryModels.Submit("subject",d.sql(),List.of(),"cr_run"))).isInstanceOf(AccessDeniedException.class);}
    @Test void revocationRemainsPossibleAfterOwnerOrSourceDisabled(){chart("ALL",false);doThrow(new AccessDeniedException("disabled")).when(accounts).active(1);policy.sharing(a,"c",new Sharing("PRIVATE",List.of(),1));verifyNoInteractions(sources);}
    @Test void recipientsMustBeExplicitDistinctActiveAndMatchMode(){chart("PRIVATE",false);assertThatThrownBy(()->policy.sharing(a,"c",new Sharing("SPECIFIC",List.of(),1))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->policy.sharing(a,"c",new Sharing("SPECIFIC",List.of(2L,2L),1))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->policy.sharing(a,"c",new Sharing("ALL",List.of(2L),1))).isInstanceOf(RuntimeException.class);doThrow(new AccessDeniedException("disabled")).when(accounts).active(2);assertThatThrownBy(()->policy.sharing(a,"c",new Sharing("SPECIFIC",List.of(2L),1))).isInstanceOf(AccessDeniedException.class);}
    @Test void adminPublicationChecksUnchangedOwnerNotAdminsBroaderPolicy(){chart("PRIVATE",false);policy.publication(admin,"c",d);verify(sources).authorizeSharing(a,"subject","PRIVATE",List.of());doThrow(new AccessDeniedException("owner no longer authorized")).when(sources).authorizeSharing(a,"subject","PRIVATE",List.of());assertThatThrownBy(()->policy.publication(admin,"c",d)).isInstanceOf(AccessDeniedException.class);}
    @Test void transferRequiresAdminAndNewOwnerQueryPolicy(){chart("ALL",false);assertThatThrownBy(()->policy.transfer(a,"c",2)).isInstanceOf(AccessDeniedException.class);when(accounts.active(2)).thenReturn(b);policy.transfer(admin,"c",2);verify(sources).authorizeSharing(b,"subject","PRIVATE",List.of());}
}
