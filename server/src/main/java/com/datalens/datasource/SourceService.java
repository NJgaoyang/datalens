package com.datalens.datasource;

import java.util.*;
import com.datalens.audit.AuditService;
import com.datalens.permission.PermissionService;
import com.datalens.common.web.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static com.datalens.datasource.SourceModels.*;

@Service
public class SourceService {
    private final SourceRepository repository;
    private final PermissionService permissions;
    private final SourceSecrets secrets;
    private final EndpointPolicy endpoints;
    private final SourcePools pools;
    private final MetadataCollector collector;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final java.util.concurrent.Semaphore harvestSlots=new java.util.concurrent.Semaphore(4);
    public SourceService(SourceRepository repository,PermissionService permissions,SourceSecrets secrets,
        EndpointPolicy endpoints,SourcePools pools,MetadataCollector collector,AuditService audit,PlatformTransactionManager manager) {
        this.repository=repository;this.permissions=permissions;this.secrets=secrets;this.endpoints=endpoints;
        this.pools=pools;this.collector=collector;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public List<SourceView> list() {permissions.requireSuperAdmin();return repository.sources();}
    public List<ColumnView> metadata(String id) {permissions.requireSuperAdmin();repository.source(id,false);return repository.columns(id);}
    public SourceView save(String id,SourceInput input,String requestId) {
        var actor=permissions.requireSuperAdmin();endpoints.validate(input.host(),input.port());
        if(input.tables().stream().distinct().count()!=input.tables().size()) throw invalid("表范围包含重复项");
        boolean create=id==null;String sourceId=create?UUID.randomUUID().toString():id;
        SourceView saved=tx.execute(status->{
            // Shared existing DB lock serializes bounded source creation across app instances.
            repository.lockAdministration(actor);
            if(create && repository.db.queryForObject("SELECT COUNT(*) FROM ds_source",Long.class)>=100)
                throw new DomainException(422,"SOURCE_LIMIT","最多可配置 100 个数据源");
            String credential;
            if(input.password()!=null && !input.password().isBlank()) credential=secrets.encrypt(sourceId,input.password());
            else if(create) throw invalid("新增数据源必须提供只读凭据");
            else credential=repository.source(sourceId,true).credential();
            if(create) repository.create(sourceId,input,credential);else repository.update(sourceId,input,credential);
            audit.append(actor.id(),create?"SOURCE_CREATE":"SOURCE_UPDATE","SOURCE",sourceId,"SUCCESS",requestId);
            return repository.source(sourceId,false).view();
        });
        pools.invalidate(sourceId,saved.version());return saved;
    }
    public void test(String id,String requestId) {
        var actor=permissions.requireSuperAdmin();var source=repository.source(id,false);
        try(var connection=pools.open(source);var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);statement.setMaxRows(1);
            try(var rows=statement.executeQuery("SELECT 1")) {if(!rows.next()) throw new java.sql.SQLException();}
            tx.executeWithoutResult(status->{
                repository.lockAdministration(actor);verifyVersion(source);
                audit.append(actor.id(),"SOURCE_TEST","SOURCE",id,"SUCCESS",requestId);
            });
        } catch(DomainException | org.springframework.security.core.AuthenticationException | org.springframework.security.access.AccessDeniedException e) {throw e;} catch(Exception e) {
            audit.append(actor.id(),"SOURCE_TEST","SOURCE",id,"FAILED",requestId);
            throw new DomainException(502,"SOURCE_CONNECTION_FAILED","数据源连接测试失败，请检查配置、权限和 TLS");
        }
    }
    public SourceView harvest(String id,String requestId) {
        var actor=permissions.requireSuperAdmin();
        if(!harvestSlots.tryAcquire()) throw new DomainException(429,"HARVEST_CAPACITY","采集任务已达并发上限");
        try { return harvestAcquired(id,requestId,actor); } finally { harvestSlots.release(); }
    }
    private SourceView harvestAcquired(String id,String requestId,com.datalens.identity.CurrentActor actor) {
        StoredSource source=tx.execute(status->{
            repository.lockAdministration(actor);
            var current=repository.source(id,true);
            if(!current.view().enabled()) throw new DomainException(409,"SOURCE_DISABLED","数据源已停用");
            if("RUNNING".equals(current.view().harvestStatus()) &&
                repository.db.queryForObject("SELECT COUNT(*) FROM ds_source WHERE id=? AND harvest_started_at > CURRENT_TIMESTAMP - INTERVAL 2 MINUTE",Long.class,id)>0)
                throw new DomainException(409,"HARVEST_RUNNING","元数据采集已在运行");
            // A new generation also prevents a stale/crashed harvester from publishing after recovery.
            repository.db.update("UPDATE ds_source SET harvest_status='RUNNING',harvest_started_at=CURRENT_TIMESTAMP(6),version=version+1,last_error_code=NULL WHERE id=?",id);
            return repository.source(id,false);
        });
        try(var connection=pools.open(source)) {
            var columns=collector.collect(connection,source.view());
            return tx.execute(status->{
                repository.lockAdministration(actor);
                verifyVersion(source);
                repository.snapshot(id,columns);
                audit.append(actor.id(),"SOURCE_HARVEST","SOURCE",id,"SUCCESS",requestId);
                return repository.source(id,false).view();
            });
        } catch(Exception e) {
            String code=e instanceof DomainException d && "METADATA_LIMIT".equals(d.code())?"METADATA_LIMIT":"METADATA_HARVEST_FAILED";
            tx.executeWithoutResult(status->{
                repository.db.update("UPDATE ds_source SET harvest_status='FAILED',last_error_code=? WHERE id=? AND version=?",code,id,source.view().version());
                audit.append(actor.id(),"SOURCE_HARVEST","SOURCE",id,"FAILED",requestId);
            });
            if(e instanceof org.springframework.security.core.AuthenticationException authentication) throw authentication;
            if(e instanceof org.springframework.security.access.AccessDeniedException denied) throw denied;
            if(e instanceof DomainException domain) throw domain;
            throw new DomainException(502,code,"元数据采集失败；已保留上次成功的结构");
        }
    }
    private void verifyVersion(StoredSource source) {
        if(repository.source(source.view().id(),true).view().version()!=source.view().version())
            throw new DomainException(409,"SOURCE_CHANGED","数据源配置已变更，请重试");
    }
    static DomainException invalid(String message) {return new DomainException(400,"INVALID_SOURCE_SCOPE",message);}
}
