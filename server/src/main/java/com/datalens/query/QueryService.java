package com.datalens.query;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.datasource.QuerySourceAccess;
import com.datalens.common.web.DomainException;
import static com.datalens.query.QueryModels.*;

@Service
public class QueryService {
    /** Server-only binding. Never accepted in the public query request DTO. */
    public record QueryContext(String purpose,String resourceId,String revisionId) {}
    private ObjectProvider<QueryPurposeAuthorization> purposeAuthorizations;
    @Autowired
    public void setPurposeAuthorizations(ObjectProvider<QueryPurposeAuthorization> purposeAuthorizations) {
        this.purposeAuthorizations=purposeAuthorizations;
    }
    private QueryPurposeAuthorization authorizePurpose(CurrentActor actor,QueryContext context) {
        if(context==null)return null;
        if(context.purpose()==null || context.purpose().isBlank() || context.resourceId()==null || context.resourceId().isBlank())
            throw new AccessDeniedException("Invalid query purpose binding");
        var guards=purposeAuthorizations==null?List.<QueryPurposeAuthorization>of():
            purposeAuthorizations.orderedStream().filter(guard->guard.supports(context.purpose())).toList();
        if(guards.size()!=1)throw new AccessDeniedException("Unknown or ambiguous query purpose");
        var guard=guards.getFirst();guard.authorize(actor,context);return guard;
    }
    private CurrentActor sourceActor(CurrentActor actor,QueryContext context,Submit input) {
        var guard=authorizePurpose(actor,context);
        if(guard==null)return actor;
        if(input!=null)guard.validateSubmission(actor,context,input);
        var executionActor=guard.executionActor(actor,context);
        if(executionActor==null)throw new AccessDeniedException("Missing query execution authority");
        return executionActor;
    }
    static final int ROW_LIMIT=1000, TIMEOUT_SECONDS=10, MAX_RESULT_BYTES=2_000_000;
    private final QueryStore store;private final QuerySourceAccess sources;private final PermissionService permissions;
    private final QueryLease lease;private final ObjectMapper json;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
        Thread.ofPlatform().name("query-worker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2,Thread.ofPlatform().name("query-timeout-",0).factory());
    private final ExecutorService cancellation=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofPlatform().name("query-cancel-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String,Running> running=new ConcurrentHashMap<>();
    private volatile boolean ready;
    static final class Running {
        volatile PreparedStatement statement;volatile Connection connection;volatile String reason;
        final AtomicBoolean requested=new AtomicBoolean();volatile boolean cancelFailed;volatile boolean dispatched;
        synchronized boolean request(String reason) {
            if(requested.get())return false;
            this.reason=reason;requested.set(true);return true;
        }
        void cancelDatabase() {
            // Runs outside request threads; actual JDBC cancellation, never UI-only state changes.
            PreparedStatement active=statement;
            if(active!=null)try{active.cancel();}catch(SQLException failure){cancelFailed=true;}
        }
        synchronized void attach(PreparedStatement statement) throws SQLException {
            this.statement=statement;
            if(requested.get())throw new SQLException("Cancelled before execution");
        }
    }
    public QueryService(QueryStore store,QuerySourceAccess sources,PermissionService permissions,QueryLease lease,ObjectMapper json) {
        this.store=store;this.sources=sources;this.permissions=permissions;this.lease=lease;this.json=json;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() throws SQLException {lease.acquire();store.recover();ready=true;}
    public TaskView submit(Submit input,String requestId) {
        return submitForPurpose(input,null,requestId);
    }
    public TaskView submitForPurpose(Submit input,QueryContext context,String requestId) {
        if(!ready || !lease.valid())throw new DomainException(503,"QUERY_EXECUTOR_UNAVAILABLE","查询执行器暂不可用");
        CurrentActor actor=permissions.current();var executionActor=sourceActor(actor,context,input);var scope=sources.authorize(executionActor,input.subjectId());
        input.parameters().forEach(QueryParameters::value);
        var validated=SafeSql.validate(input.sql(),scope.columns(),input.parameters());
        String params=json.writeValueAsString(input.parameters());
        if(params.getBytes(StandardCharsets.UTF_8).length>60000)throw new DomainException(422,"QUERY_PARAMETERS_TOO_LARGE","查询参数总大小超出限制");
        String hash=digest(input.subjectId()+"\n"+validated.sql()+"\n"+params+"\n"+scope.sourceVersion()
            +(context==null?"":"\n"+json.writeValueAsString(context)));
        var task=context==null?store.create(actor,input,scope.sourceId(),scope.sourceVersion(),hash,digest(validated.sql()),params,requestId):
            store.create(actor,input,scope.sourceId(),scope.sourceVersion(),hash,digest(validated.sql()),params,requestId,context);
        // Includes idempotent retrieval: a previous binding can never be bypassed by a new submission.
        if(!Objects.equals(context,task.context()))throw new AccessDeniedException("Query purpose changed");
        authorized(task.view().id());
        if("QUEUED".equals(task.view().status())) {
            var control=new Running();
            if(running.putIfAbsent(task.view().id(),control)==null) {
                try{executor.execute(()->execute(task.view().id(),actor,input,scope,validated.sql(),context,control));}
                catch(RejectedExecutionException failure){running.remove(task.view().id(),control);store.finish(task.view().id(),"FAILED","EXECUTOR_FULL",0,null,false);}
            }
        }
        return get(task.view().id());
    }
    QueryStore.Stored authorized(String id) {
        var actor=permissions.current();var task=store.get(id);
        if(task.actorId()!=actor.id())throw new AccessDeniedException("Query belongs to another user");
        var executionActor=sourceActor(actor,task.context(),null);
        var scope=sources.authorize(executionActor,task.view().subjectId());
        if(task.authVersion()!=actor.authVersion() || !task.sourceId().equals(scope.sourceId()) || task.sourceVersion()!=scope.sourceVersion())
            throw new AccessDeniedException("Query authorization changed");
        // Recheck all referenced fields/functions on every status/result read after scope revocation.
        var params=Arrays.asList(json.readValue(task.parametersJson(),Parameter[].class));
        SafeSql.validate(task.sql(),scope.columns(),params);
        return task;
    }
    public QueryContext context(String id){return authorized(id).context();}
    public TaskView get(String id){return authorized(id).view();}
    public Result result(String id) {
        var task=authorized(id);
        if(!"SUCCEEDED".equals(task.view().status()))throw new DomainException(409,"QUERY_RESULT_UNAVAILABLE","查询尚未成功完成");
        Result result=json.readValue(task.resultJson(),Result.class);
        authorized(id);return result;
    }
    public TaskView cancel(String id) {
        authorized(id);
        if(store.cancelQueued(id)) {
            var queued=running.get(id);if(queued!=null)queued.request("CANCELLED");
            return get(id);
        }
        if(store.cancelling(id)) {
            var control=running.get(id);
            if(control!=null)requestCancellation(id,control,"CANCELLED");
            else store.finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",0,null,false);
        }
        return get(id);
    }
    private void execute(String id,CurrentActor actor,Submit input,QuerySourceAccess.Scope scope,String sql,QueryContext context,Running control) {
        long started=System.nanoTime();ScheduledFuture<?> timeout=null;
        try {
            if(control.requested.get() || !store.start(id)) {store.finish(id,"CANCELLED",null,elapsed(started),null,false);return;}
            if(!lease.valid())throw new DomainException(503,"QUERY_LEASE_LOST","查询执行器租约失效");
            // Executor queue delay never grants stale permissions.
            var executionActor=sourceActor(actor,context,null);
            var current=sources.authorize(executionActor,input.subjectId());
            if(!scope.equals(current))throw new AccessDeniedException("Scope changed");
            SafeSql.validate(sql,current.columns(),input.parameters());
            try(Connection connection=sources.open(executionActor,input.subjectId(),scope)) {
                control.connection=connection;
                connection.setReadOnly(true);
                // Static server-side timeout is a separate barrier from client-side cancellation.
                try(Statement setup=connection.createStatement()){setup.setQueryTimeout(3);setup.execute("SET query_timeout = 10");}
                try(PreparedStatement statement=connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(TIMEOUT_SECONDS);statement.setMaxRows(ROW_LIMIT+1);statement.setFetchSize(100);
                    QueryParameters.bind(statement,input.parameters());control.attach(statement);
                    timeout=timer.schedule(()->requestCancellation(id,control,"TIMED_OUT"),TIMEOUT_SECONDS,TimeUnit.SECONDS);
                    Result result;
                    if(control.requested.get()){finishCancellation(id,control,started);return;}
                    control.dispatched=true;
                    try(ResultSet rows=statement.executeQuery()){result=read(rows,control);}
                    if(control.requested.get()) {finishCancellation(id,control,started);return;}
                    // The purpose, source revision and exact field permissions must still hold before persistence.
                    executionActor=sourceActor(actor,context,null);
                    current=sources.authorize(executionActor,input.subjectId());
                    if(!scope.equals(current))throw new AccessDeniedException("Scope changed");
                    String encoded=json.writeValueAsString(result);
                    if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES)throw new DomainException(422,"QUERY_RESULT_TOO_LARGE","查询结果超出大小限制");
                    synchronized(control) {
                        if(control.requested.get())finishCancellation(id,control,started);
                        else store.finish(id,"SUCCEEDED",null,elapsed(started),encoded,result.truncated());
                    }
                }
            }
        } catch(Exception failure) {
            if(control.requested.get())finishCancellation(id,control,started);
            else store.finish(id,failure instanceof SQLException?"CANCEL_UNCONFIRMED":"FAILED",failure instanceof AccessDeniedException?"QUERY_ACCESS_REVOKED":
                failure instanceof DomainException domain?domain.code():failure instanceof SQLTimeoutException?"DATABASE_TIMEOUT":"QUERY_EXECUTION_FAILED",elapsed(started),null,false);
        } finally {
            if(timeout!=null)timeout.cancel(false);control.statement=null;control.connection=null;running.remove(id,control);
        }
    }
    private void requestCancellation(String id,Running control,String reason) {
        if(!control.request(reason))return;
        try{cancellation.execute(control::cancelDatabase);}catch(RejectedExecutionException rejected){control.cancelFailed=true;store.finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",0,null,false);}
        timer.schedule(()->{
            if(running.get(id)==control) {
                control.cancelFailed=true;
                store.finish(id,"CANCEL_UNCONFIRMED","CANCEL_UNCONFIRMED",0,null,false);
                Connection connection=control.connection;
                if(connection!=null)try{cancellation.execute(()->{try{connection.abort(Runnable::run);}catch(SQLException ignored){}});}catch(RejectedExecutionException ignored){}
            }
        },3,TimeUnit.SECONDS);
    }
    private void finishCancellation(String id,Running control,long started) {
        String state=control.cancelFailed || control.dispatched?"CANCEL_UNCONFIRMED":control.reason;
        store.finish(id,state,state.equals("CANCELLED")?null:state,elapsed(started),null,false);
    }
    static Result read(ResultSet rows,Running control) throws SQLException {
        var metadata=rows.getMetaData();int count=metadata.getColumnCount();
        if(count>100)throw new SQLException("Too many output columns");
        var columns=new ArrayList<Column>();Set<String> names=new HashSet<>();
        for(int i=1;i<=count;i++) {
            String name=metadata.getColumnLabel(i);
            if(name==null||name.length()>128||!names.add(name))throw new SQLException("Invalid or duplicate output name");
            columns.add(new Column(name,metadata.getColumnTypeName(i)));
        }
        var result=new ArrayList<List<String>>();int bytes=0;boolean truncated=false;
        while(rows.next()) {
            if(control.requested.get())throw new SQLException("Cancelled");
            if(result.size()==ROW_LIMIT){truncated=true;break;}
            var row=new ArrayList<String>();
            for(int i=1;i<=count;i++) {
                String value=rows.getString(i);
                if(value!=null){bytes+=value.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_RESULT_BYTES)throw new SQLException("Result size limit");}
                row.add(value);
            }
            result.add(Collections.unmodifiableList(row));
        }
        return new Result(List.copyOf(columns),List.copyOf(result),truncated,ROW_LIMIT);
    }
    static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    static String digest(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @PreDestroy public void stop() {
        ready=false;running.forEach((id,c)->requestCancellation(id,c,"CANCELLED"));executor.shutdown();
        try{if(!executor.awaitTermination(15,TimeUnit.SECONDS))executor.shutdownNow();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();executor.shutdownNow();}
        timer.shutdownNow();cancellation.shutdownNow();
    }
}
