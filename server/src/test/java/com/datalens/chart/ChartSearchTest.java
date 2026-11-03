package com.datalens.chart;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.common.api.PageResult;
import com.datalens.common.web.DomainException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.query.QueryService;
import tools.jackson.databind.json.JsonMapper;

class ChartSearchTest {
    ChartStore store;QueryService queries;PermissionService permissions;ChartPermissionService policy;ChartService service;
    final CurrentActor actor=new CurrentActor(7,"viewer","USER",1);
    @BeforeEach void setup(){store=mock(ChartStore.class);queries=mock(QueryService.class);permissions=mock(PermissionService.class);policy=mock(ChartPermissionService.class);when(permissions.current()).thenReturn(actor);service=new ChartService(store,queries,permissions,JsonMapper.builder().build(),policy);}
    ChartModels.Summary chart(String id,String name){return new ChartModels.Summary(id,name,8,1,"r",Instant.EPOCH,"ALL",false);}
    void candidates(List<ChartModels.Summary> rows){
        when(store.list(eq(actor),eq("all"),anyInt(),eq(200))).thenAnswer(call->{int page=call.getArgument(2);int from=Math.min((page-1)*200,rows.size());return new PageResult<>(rows.subList(from,Math.min(from+200,rows.size())),rows.size(),page,200);});
    }
    void allow(ChartModels.Summary c){when(policy.view(actor,c.id())).thenReturn(c);when(policy.executionOwner(actor,c.id(),c.revisionId())).thenReturn(actor);}
    @Test void searchFiltersRevokedMetadataBeforeCountsAndNeverQueries(){
        var visible=chart("allowed","Sales");var hidden=chart("revoked","Sales private title");candidates(List.of(visible,hidden));allow(visible);
        when(policy.executionOwner(actor,"revoked","r")).thenThrow(new AccessDeniedException("source disabled"));
        var result=service.list("all",1,20,"SaLeS");assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(visible);verifyNoInteractions(queries);
    }
    @Test void deliveryRecheckOmitsNewlyRevokedAsset(){
        var c=chart("chart","Sales");candidates(List.of(c));allow(c);when(policy.executionOwner(actor,"chart","r")).thenReturn(actor).thenThrow(new AccessDeniedException("revoked"));
        assertThat(service.list("all",1,20,"sales").total()).isZero();verifyNoInteractions(queries);
    }
    @Test void moreThanOneThousandRevokedCandidatesStillReturnAnExactEmptyResult(){
        var rows=new ArrayList<ChartModels.Summary>();for(int i=0;i<1201;i++)rows.add(chart("revoked-"+i,"Secret title"));candidates(rows);
        when(policy.executionOwner(eq(actor),anyString(),eq("r"))).thenThrow(new AccessDeniedException("revoked"));
        var result=service.list("all",1,20);assertThat(result.total()).isZero();assertThat(result.items()).isEmpty();verify(store).list(actor,"all",7,200);verifyNoInteractions(queries);
    }
    @Test void uniqueChineseSearchBeyondOneThousandNamesIsStillFound(){
        var rows=new ArrayList<ChartModels.Summary>();for(int i=0;i<1201;i++)rows.add(chart("id-"+i,"中文报表 "+i));var target=chart("target","唯一目标 %_! \\ '");rows.add(target);candidates(rows);allow(target);
        var result=service.list("all",1,20,"唯一目标 %_! \\ '");assertThat(result.total()).isEqualTo(1);assertThat(result.items()).containsExactly(target);verify(policy,times(2)).executionOwner(actor,"target","r");verifyNoInteractions(queries);
    }
    @Test void authorizedResultsHaveNoArtificialCountCap(){
        var rows=new ArrayList<ChartModels.Summary>();for(int i=0;i<1201;i++){var c=chart("id-"+i,"Sales");rows.add(c);allow(c);}candidates(rows);
        var result=service.list("all",61,20," SALES ");assertThat(result.total()).isEqualTo(1201);assertThat(result.items()).containsExactly(rows.getLast());verifyNoInteractions(queries);
    }
    @Test void onlyTwoConcurrentScansAreAdmittedAndSlotsRecover() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(store.list(actor,"all",1,200)).thenAnswer(call->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");return new PageResult<>(List.of(),0,1,200);});
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->service.list("all",1,20));var second=executor.submit(()->service.list("all",1,20));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->service.list("all",1,20,"private title")).isInstanceOfSatisfying(DomainException.class,e->{assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("CHART_LIST_BUSY");assertThat(e.getMessage()).isEqualTo("图表列表服务繁忙，请稍后重试");});
                verify(store,times(2)).list(actor,"all",1,200);
            }finally{release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS).total()).isZero();assertThat(second.get(5,TimeUnit.SECONDS).total()).isZero();
        }
        assertThat(service.list("all",1,20).total()).isZero();verifyNoInteractions(queries);
    }
    @Test void failuresAndInvalidSearchAlwaysReleaseSlots(){
        when(store.list(actor,"all",1,200)).thenThrow(new IllegalStateException("metadata unavailable"));
        for(int i=0;i<3;i++)assertThatThrownBy(()->service.list("all",1,20)).isInstanceOf(IllegalStateException.class);
        for(int i=0;i<3;i++)assertThatThrownBy(()->service.list("all",1,20,"x".repeat(101))).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isNotEqualTo("CHART_LIST_BUSY"));
        candidates(List.of());assertThat(service.list("all",1,20).total()).isZero();
    }
    @Test void principalAndScopeAreValidatedBeforeAnyScanAdmission(){
        when(permissions.current()).thenThrow(new AccessDeniedException("disabled"));assertThatThrownBy(()->service.list("all",1,20)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(store);
        doReturn(actor).when(permissions).current();doThrow(new AccessDeniedException("Administrator required")).when(store).validateListScope(actor,"admin");
        for(int i=0;i<3;i++)assertThatThrownBy(()->service.list("admin",1,20)).isInstanceOf(AccessDeniedException.class);
        candidates(List.of());assertThat(service.list("all",1,20).total()).isZero();verify(store,never()).list(eq(actor),eq("admin"),anyInt(),anyInt());
    }
    @Test void trashWithoutSearchAlsoRechecksOwnershipBeforeDelivery(){
        var c=new ChartModels.Summary("trash","Sales",7,1,"r",Instant.EPOCH,"PRIVATE",true);
        when(store.list(actor,"trash",1,200)).thenReturn(new PageResult<>(List.of(c),1,1,200));when(policy.manage(actor,"trash",true)).thenReturn(c).thenThrow(new AccessDeniedException("owner changed"));
        assertThat(service.list("trash",1,20).total()).isZero();verifyNoInteractions(queries);
    }
}
