package com.datalens.datasource;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.permission.PermissionService;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

@Service
public class SubjectService {
    private final SourceRepository repository;
    private final PermissionService permissions;
    private final AuditService audit;
    public SubjectService(SourceRepository repository,PermissionService permissions,AuditService audit) {
        this.repository=repository;this.permissions=permissions;this.audit=audit;
    }
    @Transactional(readOnly=true) public List<SubjectView> adminList() {permissions.requireSuperAdmin();return repository.subjects();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public SubjectView save(String id,SubjectInput input,String requestId) {
        var actor=permissions.requireSuperAdmin();
        repository.lockAdministration(actor);
        boolean create=id==null;
        if(create && repository.database().queryForObject("SELECT COUNT(*) FROM ds_subject",Long.class)>=100)
            throw new DomainException(422,"SUBJECT_LIMIT","最多可配置 100 个业务主题");
        if(!create) repository.subject(id);
        repository.source(input.sourceId(),true);
        Set<Field> known=new HashSet<>();repository.columns(input.sourceId()).forEach(c->known.add(new Field(c.tableName(),c.columnName())));
        if(!known.containsAll(input.fields()) || !known.containsAll(input.deniedFields()))
            throw SourceService.invalid("主题字段必须存在于最新采集的授权元数据中");
        for(long user:new HashSet<>(input.userIds()))
            if(repository.database().queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND enabled=TRUE",Long.class,user)==0)
                throw SourceService.invalid("授权用户不存在或已停用");
        String subjectId=create?UUID.randomUUID().toString():id;
        repository.saveSubject(subjectId,input,create);
        audit.append(actor.id(),create?"SUBJECT_CREATE":"SUBJECT_UPDATE","SUBJECT",subjectId,"SUCCESS",requestId);
        return repository.subject(subjectId);
    }
    @Transactional(readOnly=true) public List<SubjectView> list() {
        var actor=permissions.current();
        return repository.subjects().stream().filter(s->"SUPER_ADMIN".equals(actor.role()) || s.userIds().contains(actor.id()))
            .filter(s->repository.source(s.sourceId(),false).view().enabled())
            .map(s->new SubjectView(s.id(),s.name(),s.sourceId(),effective(s).stream().map(c->new Field(c.tableName(),c.columnName())).toList(),
                List.of(),List.of(),s.shareScope())).toList();
    }
    @Transactional(readOnly=true) public List<ColumnView> metadata(String id) {
        var actor=permissions.current();var subject=repository.subject(id);
        if(!"SUPER_ADMIN".equals(actor.role()) && !subject.userIds().contains(actor.id()))
            throw new AccessDeniedException("Subject access denied");
        if(!repository.source(subject.sourceId(),false).view().enabled())
            throw new DomainException(409,"SOURCE_DISABLED","数据源已停用");
        return effective(subject);
    }
    private List<ColumnView> effective(SubjectView subject) {
        var allowed=new HashSet<>(subject.fields());allowed.removeAll(subject.deniedFields());
        return repository.columns(subject.sourceId()).stream()
            .filter(c->allowed.contains(new Field(c.tableName(),c.columnName()))).toList();
    }
}
