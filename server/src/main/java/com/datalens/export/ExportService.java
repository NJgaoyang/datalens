package com.datalens.export;
import java.time.Instant;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.datalens.chart.*;
import com.datalens.identity.*;
import com.datalens.permission.PermissionService;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.export.ExportModels.*;
@Service
public class ExportService {
 private final ExportStore store;private final ChartService charts;private final ChartPermissionService policy;private final PermissionService permissions;private final AccountDirectory accounts;private final AuditService audit;
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),Thread.ofPlatform().name("export-worker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 private final ScheduledExecutorService cleanup=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("export-cleanup-",0).factory());
 private final java.util.Set<String> scheduled=ConcurrentHashMap.newKeySet();
 private volatile boolean ready;
 public ExportService(ExportStore store,ChartService charts,ChartPermissionService policy,PermissionService permissions,AccountDirectory accounts,AuditService audit){this.store=store;this.charts=charts;this.policy=policy;this.permissions=permissions;this.accounts=accounts;this.audit=audit;}
 @org.springframework.core.annotation.Order(20) @EventListener(ApplicationReadyEvent.class) public void start(){store.recover();ready=true;cleanup.scheduleWithFixedDelay(()->{try{store.cleanup();}catch(RuntimeException ignored){/* Retry; no sensitive data in logs. */}},60,60,TimeUnit.SECONDS);}
 private ChartModels.Summary authorized(CurrentActor actor,String chart){actor=permissions.requireActive(actor);var c=policy.view(actor,chart);policy.executionOwner(actor,chart,c.revisionId());if(store.grant(chart,actor.id(),c.version())==0)throw new AccessDeniedException("Independent export grant required");return c;}
 void check(CurrentActor actor,ExportStore.Row row){if(row.actor()!=actor.id()||row.authVersion()!=actor.authVersion())throw new AccessDeniedException("Export ownership denied");if(!row.expires().isAfter(Instant.now()))throw new DomainException(410,"EXPORT_EXPIRED","导出已过期");var c=authorized(actor,row.chart());if(c.version()!=row.chartVersion()||!c.revisionId().equals(row.revision())||store.grant(c.id(),actor.id(),c.version())!=row.generation())throw new AccessDeniedException("Export authority changed");charts.authorizeResultTask(row.chart(),row.task());}
 public java.util.List<GrantView> grants(String chart){var actor=permissions.requireSuperAdmin();var c=policy.view(actor,chart);return store.grants(chart,c.version());}
 public Permission permission(String chart){var actor=permissions.current();policy.view(actor,chart);try{authorized(actor,chart);return new Permission(true);}catch(AccessDeniedException|DomainException denied){return new Permission(false);}}
 public Permission grant(String chart,Grant input,String request){var actor=permissions.requireSuperAdmin();Runnable validate=()->{permissions.requireSuperAdmin();var c=policy.view(actor,chart);if(c.version()!=input.expectedChartVersion())throw new DomainException(409,"EXPORT_CONFLICT","图表版本已改变");if(input.enabled()){var recipient=accounts.active(input.userId());policy.executionOwner(recipient,chart,c.revisionId());}};store.grant(actor,chart,input,validate,request);return permission(chart);}
 public Job create(Create input,String request){if(!ready)throw new DomainException(503,"EXPORT_UNAVAILABLE","导出暂不可用");var actor=permissions.current();var c=authorized(actor,input.chartId());var result=charts.result(c.id(),input.taskId());if(result.truncated())throw new DomainException(422,"EXPORT_TRUNCATED","结果已截断，请缩小范围后刷新");long generation=store.grant(c.id(),actor.id(),c.version());var row=store.create(actor,c,generation,input,()->{var now=authorized(actor,c.id());if(now.version()!=c.version()||store.grant(c.id(),actor.id(),c.version())!=generation)throw new AccessDeniedException("Export authority changed");},request);check(actor,row);if(row.status().equals("QUEUED")&&scheduled.add(row.id()))try{executor.execute(()->{try{run(actor,row,request);}finally{scheduled.remove(row.id());}});}catch(RejectedExecutionException full){scheduled.remove(row.id());store.fail(row.id(),"EXPORT_CAPACITY");}return get(row.id());}
 private void run(CurrentActor actor,ExportStore.Row row,String request){if(!store.claim(row.id()))return;var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,java.util.List.of()));SecurityContextHolder.setContext(context);try{check(actor,row);byte[] bytes=SafeCsv.encode(charts.result(row.chart(),row.task()));check(actor,row);store.finish(row.id(),bytes);audit.append(actor.id(),"EXPORT_BUILD","EXPORT",row.id(),"SUCCEEDED",request);}catch(RuntimeException failure){store.fail(row.id(),"EXPORT_FAILED");audit.append(actor.id(),"EXPORT_BUILD","EXPORT",row.id(),"FAILED",request);}finally{SecurityContextHolder.clearContext();}}
 public Job get(String id){var row=store.get(id);check(permissions.current(),row);return row.view();}
 public byte[] download(String id,String request){var actor=permissions.current();var row=store.get(id);check(actor,row);if(!row.status().equals("SUCCEEDED"))throw new DomainException(409,"EXPORT_NOT_READY","导出尚未成功");byte[] bytes=store.content(id);check(permissions.current(),row);audit.append(actor.id(),"EXPORT_DOWNLOAD","EXPORT",id,"SUCCEEDED",request);return bytes;}
 @PreDestroy public void close(){ready=false;executor.shutdownNow();cleanup.shutdownNow();}
}
