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
    /** Sharing is a resource capability; it does not grant recipients arbitrary subject queries. */
    @Transactional(readOnly=true) public Scope authorizeSharing(CurrentActor owner,String subjectId,String visibility,List<Long> recipients) {
        Scope scope=authorize(owner,subjectId);
        var subject=repository.subject(subjectId);
        if(recipients==null || recipients.stream().anyMatch(id->id==null || id<=0))
            throw new AccessDeniedException("Invalid sharing recipients");
        if("PRIVATE".equals(visibility)) {
            if(!recipients.isEmpty())throw new AccessDeniedException("Private chart has recipients");
            return scope;
        }
        if("ALL".equals(visibility)) {
            if(!"ALL_AUTHENTICATED".equals(subject.shareScope()) || !recipients.isEmpty())
                throw new AccessDeniedException("Subject does not permit sharing to all users");
        } else if("SPECIFIC".equals(visibility)) {
            if(recipients.isEmpty() || !("ALL_AUTHENTICATED".equals(subject.shareScope()) ||
                "AUTHORIZED_USERS".equals(subject.shareScope()) && subject.userIds().containsAll(recipients)))
                throw new AccessDeniedException("Subject does not permit these sharing recipients");
        } else throw new AccessDeniedException("Invalid chart visibility");
        return scope;
    }
    public Connection open(CurrentActor actor,String subjectId,Scope expected) throws SQLException {
        Scope current=authorize(actor,subjectId);
        if(!current.equals(expected)) throw new AccessDeniedException("Query scope changed");
        var source=repository.source(current.sourceId(),false);
        if(source.view().version()!=expected.sourceVersion()) throw new AccessDeniedException("Source changed");
        return pools.open(source);
    }
}
