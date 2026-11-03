package com.datalens.query;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.datasource.QuerySourceAccess;
import com.datalens.datasource.SourceModels.ColumnView;
import org.springframework.security.access.AccessDeniedException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.datalens.query.QueryModels.*;
class QueryServiceTest {
    QueryStore store;QuerySourceAccess sources;PermissionService permissions;QueryLease lease;QueryService service;
    final CurrentActor actor=new CurrentActor(7,"reader","USER",1);
    final QuerySourceAccess.Scope scope=new QuerySourceAccess.Scope("source",1,List.of(new ColumnView("source","orders","amount","DECIMAL",1,false)));
    @BeforeEach void setup() throws Exception {
        store=mock(QueryStore.class);sources=mock(QuerySourceAccess.class);permissions=mock(PermissionService.class);lease=mock(QueryLease.class);
        service=new QueryService(store,sources,permissions,lease,JsonMapper.builder().build());
        when(permissions.current()).thenReturn(actor);when(sources.authorize(actor,"subject")).thenReturn(scope);when(lease.valid()).thenReturn(true);service.start();
    }
    @AfterEach void stop(){service.stop();}
    QueryStore.Stored task(long owner,long version,String status) {
        return new QueryStore.Stored(new TaskView("task","subject",status,null,Instant.now(),null,null,false),owner,version,"source",1,"hash",null,"SELECT amount FROM orders","[]");
    }
    @Test void otherUserCannotReadOrCancelTask() {
        when(store.get("task")).thenReturn(task(8,1,"RUNNING"));
        assertThatThrownBy(()->service.get("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.cancel("task")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).cancelling(anyString());verify(sources,never()).authorize(any(),anyString());
    }
    @Test void passwordChangeInvalidatesPreviousResults() {
        when(store.get("task")).thenReturn(task(7,2,"SUCCEEDED"));
        assertThatThrownBy(()->service.result("task")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void fieldRevocationBlocksStatusAndResults() {
        when(store.get("task")).thenReturn(task(7,1,"SUCCEEDED"));
        when(sources.authorize(actor,"subject")).thenReturn(new QuerySourceAccess.Scope("source",1,List.of(new ColumnView("source","orders","id","BIGINT",1,false))));
        assertThatThrownBy(()->service.get("task")).hasMessageContaining("SQL");
        assertThatThrownBy(()->service.result("task")).hasMessageContaining("SQL");
    }
    @Test void sourceRevisionChangeBlocksOldResults() {
        when(store.get("task")).thenReturn(task(7,1,"SUCCEEDED"));
        when(sources.authorize(actor,"subject")).thenReturn(new QuerySourceAccess.Scope("source",2,scope.columns()));
        assertThatThrownBy(()->service.result("task")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void duplicateSubmitsDispatchOnce() throws Exception {
        var input=new Submit("subject","SELECT amount FROM orders",List.of(),"same-request-1");
        when(store.create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString())).thenReturn(task(7,1,"QUEUED"));
        when(store.get("task")).thenReturn(task(7,1,"RUNNING"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(store.start("task")).thenAnswer(inv->{entered.countDown();release.await(5,TimeUnit.SECONDS);return false;});
        service.submit(input,"request");assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();service.submit(input,"request");
        verify(store,times(1)).start("task");release.countDown();
    }
    @Test void cancelRequestsActualJdbcCancellation() throws Exception {
        var control=new QueryService.Running();var statement=mock(PreparedStatement.class);control.attach(statement);
        assertThat(control.request("CANCELLED")).isTrue();control.cancelDatabase();verify(statement).cancel();
        assertThat(control.request("CANCELLED")).isFalse();
    }
    @Test void cancelledBeforeStatementAttachmentCannotExecute() {
        var control=new QueryService.Running();control.request("CANCELLED");
        assertThatThrownBy(()->control.attach(mock(PreparedStatement.class))).isInstanceOf(SQLException.class);
    }
    @Test void jdbcCancellationFailureIsPreserved() throws Exception {
        var control=new QueryService.Running();var statement=mock(PreparedStatement.class);control.attach(statement);
        doThrow(new SQLException("hidden credentials")).when(statement).cancel();control.request("CANCELLED");control.cancelDatabase();assertThat(control.cancelFailed).isTrue();
    }
    @Test void rowLimitExplicitlySignalsTruncationAndPreservesDecimal() throws Exception {
        ResultSet rows=mock(ResultSet.class);ResultSetMetaData md=mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(md);when(md.getColumnCount()).thenReturn(1);when(md.getColumnLabel(1)).thenReturn("amount");when(md.getColumnTypeName(1)).thenReturn("DECIMAL");
        when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("9007199254740993.1200");
        Result result=QueryService.read(rows,new QueryService.Running());
        assertThat(result.truncated()).isTrue();assertThat(result.rows()).hasSize(1000);assertThat(result.rows().getFirst()).containsExactly("9007199254740993.1200");verify(rows,times(1001)).next();
    }
    @Test void nullCellsArePreservedAndOversizeRejected() throws Exception {
        ResultSet rows=mock(ResultSet.class);ResultSetMetaData md=mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(md);when(md.getColumnCount()).thenReturn(1);when(md.getColumnLabel(1)).thenReturn("amount");when(md.getColumnTypeName(1)).thenReturn("DECIMAL");
        when(rows.next()).thenReturn(true,false);assertThat(QueryService.read(rows,new QueryService.Running()).rows().getFirst()).containsExactly((String)null);
        when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("x".repeat(2_000_001));
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class);
    }
    @Test void parametersAreBoundNotInterpolated() throws Exception {
        var statement=mock(PreparedStatement.class);String attack="'; DROP TABLE orders; --";
        QueryParameters.bind(statement,List.of(new Parameter("STRING",attack),new Parameter("DECIMAL","123.40"),new Parameter("NULL",null)));
        verify(statement).setObject(1,attack);verify(statement).setObject(2,new java.math.BigDecimal("123.40"));verify(statement).setNull(3,Types.NULL);
        assertThatThrownBy(()->QueryParameters.value(new Parameter("BOOLEAN","TRUE"))).hasMessageContaining("参数");
        assertThatThrownBy(()->QueryParameters.value(new Parameter("INTEGER","9223372036854775808"))).hasMessageContaining("参数");
        assertThatThrownBy(()->QueryParameters.value(new Parameter("DATE","2026-02-30"))).hasMessageContaining("参数");
    }
    QueryService.QueryContext chartContext=new QueryService.QueryContext("CHART","chart","revision");
    QueryStore.Stored purposeTask(String state,QueryService.QueryContext context) {
        var direct=task(7,1,state);
        return new QueryStore.Stored(direct.view(),7,1,"source",1,"hash",null,direct.sql(),direct.parametersJson(),context);
    }
    QueryPurposeAuthorization guard() {
        var guard=mock(QueryPurposeAuthorization.class);
        when(guard.supports("CHART")).thenReturn(true);
        org.springframework.beans.factory.ObjectProvider<QueryPurposeAuthorization> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv->java.util.stream.Stream.of(guard));
        service.setPurposeAuthorizations(provider);
        return guard;
    }
    Submit purposeInput(){return new Submit("subject","SELECT amount FROM orders",List.of(),"purpose-key");}
    @Test void unknownPurposeCannotSubmitOrReadOrCancel() {
        var input=purposeInput();
        assertThatThrownBy(()->service.submitForPurpose(input,chartContext,"request")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),any());
        when(store.get("task")).thenReturn(purposeTask("SUCCEEDED",chartContext));
        assertThatThrownBy(()->service.get("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.result("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.cancel("task")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).cancelQueued(anyString());
    }
    @Test void contextRevocationBlocksAllGenericEndpointsAndIdempotentSubmission() throws Exception {
        var guard=guard();doThrow(new AccessDeniedException("Chart access revoked")).when(guard).authorize(actor,chartContext);
        when(store.get("task")).thenReturn(purposeTask("SUCCEEDED",chartContext));
        assertThatThrownBy(()->service.get("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.result("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.cancel("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.context("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.submitForPurpose(purposeInput(),chartContext,"request")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).cancelQueued(anyString());verify(store,never()).cancelling(anyString());
        verify(sources,never()).open(any(),anyString(),any());
    }
    @Test void malformedPurposeBindingFailsClosedEvenWithGuard() {
        var guard=guard();
        when(store.get("task")).thenReturn(purposeTask("SUCCEEDED",new QueryService.QueryContext(null,"chart","revision")));
        assertThatThrownBy(()->service.get("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.submitForPurpose(purposeInput(),new QueryService.QueryContext("CHART",null,"revision"),"request"))
            .isInstanceOf(AccessDeniedException.class);
        verify(guard,never()).authorize(any(),any());
    }
    @Test void chartPurposeNeverBypassesCurrentSubjectGrant() {
        guard();when(sources.authorize(actor,"subject")).thenThrow(new AccessDeniedException("Subject revoked"));
        when(store.get("task")).thenReturn(purposeTask("SUCCEEDED",chartContext));
        assertThatThrownBy(()->service.get("task")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.submitForPurpose(purposeInput(),chartContext,"request")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void publicSubmitHasNoPurposeBindingProperty() {
        assertThat(Arrays.stream(Submit.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .containsExactly("subjectId","sql","parameters","idempotencyKey");
    }
    @Test void purposeAndRevisionAreIncludedInIdempotencyHash() {
        guard();
        var hashes=new ArrayList<String>();
        when(store.create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),any()))
            .thenAnswer(inv->{hashes.add(inv.getArgument(4));var row=purposeTask("SUCCEEDED",inv.getArgument(8));when(store.get("task")).thenReturn(row);return row;});
        service.submitForPurpose(purposeInput(),chartContext,"request");
        service.submitForPurpose(purposeInput(),new QueryService.QueryContext("CHART","chart","revision2"),"request");
        service.submitForPurpose(purposeInput(),new QueryService.QueryContext("CHART","chart2","revision"),"request");
        assertThat(hashes).doesNotHaveDuplicates();
    }
    @Test void directSubmissionCannotRetrievePurposeBoundIdempotentTask() {
        guard();
        when(store.create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString())).thenReturn(purposeTask("QUEUED",chartContext));
        assertThatThrownBy(()->service.submit(purposeInput(),"request")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).start(anyString());
    }
    @Test void purposeRevocationWhileQueuedPreventsJdbcDispatch() throws Exception {
        var guard=guard();
        var revoked=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv->{if(revoked.get())throw new AccessDeniedException("Revoked");return null;}).when(guard).authorize(actor,chartContext);
        when(store.create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(purposeTask("QUEUED",chartContext));
        when(store.get("task")).thenReturn(purposeTask("RUNNING",chartContext));
        var release=new CountDownLatch(1);
        when(store.start("task")).thenAnswer(inv->{release.await(3,TimeUnit.SECONDS);return true;});
        service.submitForPurpose(purposeInput(),chartContext,"request");
        revoked.set(true);release.countDown();
        verify(store,timeout(3000)).finish(eq("task"),eq("FAILED"),eq("QUERY_ACCESS_REVOKED"),anyLong(),isNull(),eq(false));
        verify(sources,never()).open(any(),anyString(),any());
    }
    @Test void purposeRevocationDuringExecutionPreventsResultPersistence() throws Exception {
        var guard=guard();var revoked=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv->{if(revoked.get())throw new AccessDeniedException("Revoked");return null;}).when(guard).authorize(actor,chartContext);
        when(store.create(any(),any(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(purposeTask("QUEUED",chartContext));
        when(store.get("task")).thenReturn(purposeTask("RUNNING",chartContext));when(store.start("task")).thenReturn(true);
        var connection=mock(Connection.class);var setup=mock(Statement.class);var statement=mock(PreparedStatement.class);
        var rows=mock(ResultSet.class);var md=mock(ResultSetMetaData.class);var release=new CountDownLatch(1);
        when(sources.open(actor,"subject",scope)).thenReturn(connection);when(connection.createStatement()).thenReturn(setup);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(inv->{release.await(3,TimeUnit.SECONDS);revoked.set(true);return rows;});
        when(rows.getMetaData()).thenReturn(md);when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("amount");when(md.getColumnTypeName(1)).thenReturn("DECIMAL");when(rows.next()).thenReturn(false);
        service.submitForPurpose(purposeInput(),chartContext,"request");release.countDown();
        verify(store,timeout(3000)).finish(eq("task"),eq("FAILED"),eq("QUERY_ACCESS_REVOKED"),anyLong(),isNull(),eq(false));
        verify(store,never()).finish(anyString(),eq("SUCCEEDED"),any(),anyLong(),any(),anyBoolean());
    }

}
