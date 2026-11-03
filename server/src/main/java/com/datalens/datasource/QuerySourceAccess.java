package com.datalens.datasource;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

/** Narrow capability boundary: query module never obtains stored credentials or source repository. */
@Service
public class QuerySourceAccess {
    public record Scope(String sourceId,long sourceVersion,List<ColumnView> columns) {}
    private final SourceRepository repository;
    private final PermissionService permissions;
    private final SourcePools pools;
    public QuerySourceAccess(SourceRepository repository,PermissionService permissions,SourcePools pools) {
        this.repository=repository;this.permissions=permissions;this.pools=pools;
    }
    @Transactional(readOnly=true) public Scope authorize(CurrentActor actor,String subjectId) {
        var current=permissions.requireActive(actor);var subject=repository.subject(subjectId);
        if(!"SUPER_ADMIN".equals(current.role()) && !subject.userIds().contains(current.id()))
            throw new AccessDeniedException("Subject access denied");
        var source=repository.source(subject.sourceId(),false).view();
        if(!source.enabled()) throw new DomainException(409,"SOURCE_DISABLED","数据源已停用");
        Set<Field> allowed=new HashSet<>(subject.fields());allowed.removeAll(subject.deniedFields());
        var columns=repository.columns(source.id()).stream().filter(c->allowed.contains(new Field(c.tableName(),c.columnName()))).toList();
        if(columns.isEmpty()) throw new AccessDeniedException("No allowed fields");
        return new Scope(source.id(),source.version(),columns);
    }
    public Connection open(CurrentActor actor,String subjectId,Scope expected) throws SQLException {
        Scope current=authorize(actor,subjectId);
        if(!current.equals(expected)) throw new AccessDeniedException("Query scope changed");
        var source=repository.source(current.sourceId(),false);
        if(source.view().version()!=expected.sourceVersion()) throw new AccessDeniedException("Source changed");
        return pools.open(source);
    }
}
