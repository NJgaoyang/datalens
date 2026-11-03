package com.datalens.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

/** Independent lazy analysis pools. Never a Spring DataSource bean, so MySQL/Flyway remain separate. */
@Component
public class SourcePools {
    private record Entry(long version,HikariDataSource pool) {}
    private final Map<String,Entry> pools=new HashMap<>();
    private final Map<String,Long> minimumVersions=new HashMap<>();
    private final EndpointPolicy policy;
    private final SourceSecrets secrets;
    public SourcePools(EndpointPolicy policy,SourceSecrets secrets) { this.policy=policy;this.secrets=secrets; }
    protected HikariDataSource build(StoredSource source) {
        var view=source.view();var config=new HikariConfig();
        config.setPoolName("analysis-"+view.id());config.setJdbcUrl(policy.jdbcUrl(view));
        config.setUsername(view.username());config.setPassword(secrets.decrypt(view.id(),source.credential()));
        config.setMaximumPoolSize(3);config.setMinimumIdle(0);config.setConnectionTimeout(5000);
        config.setValidationTimeout(2000);config.setInitializationFailTimeout(-1);
        config.setIdleTimeout(60000);config.setMaxLifetime(300000);
        config.addDataSourceProperty("connectTimeout","5000");config.addDataSourceProperty("socketTimeout","10000");
        config.addDataSourceProperty("sslMode","VERIFY_IDENTITY");
        config.addDataSourceProperty("allowPublicKeyRetrieval","false");
        config.addDataSourceProperty("allowLoadLocalInfile","false");
        config.addDataSourceProperty("allowMultiQueries","false");
        return new HikariDataSource(config);
    }
    public Connection open(StoredSource source) throws SQLException {
        Entry entry;HikariDataSource retired=null;
        synchronized(this) {
        var view=source.view();policy.validate(view.host(),view.port());
        if(!view.enabled()) throw new DomainException(409,"SOURCE_DISABLED","数据源已停用");
        if(view.version()<minimumVersions.getOrDefault(view.id(),0L))
            throw new DomainException(409,"SOURCE_CHANGED","数据源配置已变更，请重试");
        minimumVersions.merge(view.id(),view.version(),Math::max);
        entry=pools.get(view.id());
        if(entry==null || entry.version()!=view.version()) {
            if(entry!=null) retired=entry.pool();
            if(entry==null && pools.size()>=100) throw new DomainException(429,"SOURCE_POOL_LIMIT","数据源连接池已达上限");
            entry=new Entry(view.version(),build(source));pools.put(view.id(),entry);
        }
                }
        if(retired!=null) retired.close();
        Connection connection=entry.pool().getConnection();
        synchronized(this) {
            if(pools.get(source.view().id())!=entry || source.view().version()<minimumVersions.getOrDefault(source.view().id(),0L)) {
                connection.close();throw new DomainException(409,"SOURCE_CHANGED","数据源配置已变更，请重试");
            }
        }
        return connection;
    }
    public void invalidate(String id,long version) {
        Entry retired=null;
        synchronized(this) {
            minimumVersions.merge(id,version,Math::max);
            Entry entry=pools.get(id);
            if(entry!=null && entry.version()<version) {retired=pools.remove(id);}
        }
        if(retired!=null) retired.pool().close();
    }
    @PreDestroy public void close() {
        List<Entry> retired;
        synchronized(this) {retired=List.copyOf(pools.values());pools.clear();}
        retired.forEach(e->e.pool().close());
    }
}
