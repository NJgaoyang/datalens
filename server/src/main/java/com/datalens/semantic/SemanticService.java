package com.datalens.semantic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import com.datalens.permission.PermissionService;
import com.datalens.identity.CurrentActor;
import com.datalens.datasource.QuerySourceAccess;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.semantic.SemanticModels.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class SemanticService {
    private final SemanticStore store;private final PermissionService permissions;private final QuerySourceAccess sources;private final ObjectMapper json;private final Validator validator;private final AuditService audit;
    public SemanticService(SemanticStore store,PermissionService permissions,QuerySourceAccess sources,ObjectMapper json,Validator validator,AuditService audit){this.store=store;this.permissions=permissions;this.sources=sources;this.json=json;this.validator=validator;this.audit=audit;}
    private void check(Object value){if(value==null||!validator.validate(value).isEmpty())throw SemanticRules.invalid("语义输入格式无效");}
    private Document decode(String text){return json.readValue(text,Document.class);}
    private QuerySourceAccess.Scope scope(CurrentActor actor,String id){if(id==null||id.length()>36)throw SemanticRules.invalid("主题 ID 无效");return sources.authorize(actor,id);}
    private void unchanged(String expected,QuerySourceAccess.Scope current){if(!expected.equals(SemanticRules.fingerprint(current)))throw new DomainException(409,"SEMANTIC_SCOPE_CHANGED","主题元数据或授权字段已变化，请重新保存并审核语义");}
    private SemanticStore.Workspace expected(String id,long expected){var w=store.workspace(id);if((w==null?0:w.version())!=expected)throw new DomainException(409,"SEMANTIC_VERSION_CONFLICT","语义已更新，请重新加载");return w;}
    private Draft view(String id,SemanticStore.Workspace w){if(w==null)return new Draft(id,0,null,false,null,null,null,List.of());return new Draft(id,w.version(),decode(w.document()),Objects.equals(w.digest(),w.reviewedDigest()),w.reviewerId(),w.reviewedAt(),w.publishedVersion(),SemanticRules.warnings(decode(w.document())));}
    private Draft redacted(String id,SemanticStore.Workspace w){permissions.requireSuperAdmin();return new Draft(id,w==null?0:w.version(),null,false,null,null,w==null?null:w.publishedVersion(),List.of("SEMANTIC_SCOPE_CHANGED：主题不可用或字段已变化，草稿内容已隐藏；可撤回发布或保存新的完整文档并重新审核"));}
    // Scope lookup may legitimately fail after revocation; do not catch a failure inside a shared
    // transaction and accidentally return a rollback-only transaction as a successful recovery read.
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Draft draft(String id){var actor=permissions.requireSuperAdmin();QuerySourceAccess.Scope current;
        try {current=scope(actor,id);} catch(DomainException failure){if(!"SOURCE_DISABLED".equals(failure.code()))throw failure;return redacted(id,store.workspace(id));}
        catch(org.springframework.security.access.AccessDeniedException failure){return redacted(id,store.workspace(id));}
        var w=store.workspace(id);
        if(w!=null && !w.scope().equals(SemanticRules.fingerprint(current)))return redacted(id,w);
        if(w!=null)SemanticRules.validate(decode(w.document()),current);unchanged(SemanticRules.fingerprint(current),scope(actor,id));return view(id,w);}
    public Draft save(String id,Save input,String requestId){check(input);var actor=permissions.requireSuperAdmin();store.lock();permissions.requireSuperAdmin();var current=scope(actor,id);expected(id,input.expectedVersion());String document=json.writeValueAsString(input.document());if(document.getBytes(StandardCharsets.UTF_8).length>500000)throw SemanticRules.invalid("语义文档超过 500 KB");SemanticRules.validate(input.document(),current);unchanged(SemanticRules.fingerprint(current),scope(actor,id));store.save(id,input.expectedVersion(),document,SemanticRules.digest(document),SemanticRules.fingerprint(current));audit.append(actor.id(),"SEMANTIC_SAVE","SUBJECT",id,"SUCCEEDED",requestId);return view(id,store.workspace(id));}
    public Draft review(String id,Review input,String requestId){check(input);if(!input.confirmation())throw SemanticRules.invalid("必须明确确认人工审核业务口径和例题");var actor=permissions.requireSuperAdmin();store.lock();permissions.requireSuperAdmin();var current=scope(actor,id);var w=expected(id,input.expectedVersion());if(w==null)throw missing();unchanged(w.scope(),current);SemanticRules.validate(decode(w.document()),current);unchanged(w.scope(),scope(actor,id));store.review(id,actor.id());audit.append(actor.id(),"SEMANTIC_REVIEW","SUBJECT",id,"SUCCEEDED",requestId);return view(id,store.workspace(id));}
    public Publication publish(String id,Version input,String requestId){check(input);var actor=permissions.requireSuperAdmin();store.lock();permissions.requireSuperAdmin();var current=scope(actor,id);var w=expected(id,input.expectedVersion());if(w==null)throw missing();unchanged(w.scope(),current);SemanticRules.validate(decode(w.document()),current);if(w.reviewerId()==null||!w.digest().equals(w.reviewedDigest()))throw new DomainException(409,"SEMANTIC_NOT_REVIEWED","草稿尚未经过人工审核");if(w.nextRelease()>1000)throw SemanticRules.invalid("主题最多保留 1000 个发布版本");unchanged(w.scope(),scope(actor,id));long version=store.publish(w,actor.id());audit.append(actor.id(),"SEMANTIC_PUBLISH","SUBJECT",id,"SUCCEEDED",requestId);return publication(store.release(id,version));}
    public Draft withdraw(String id,Version input,String requestId){check(input);var actor=permissions.requireSuperAdmin();store.lock();permissions.requireSuperAdmin();var w=expected(id,input.expectedVersion());if(w==null)throw missing();store.withdraw(id);audit.append(actor.id(),"SEMANTIC_WITHDRAW","SUBJECT",id,"SUCCEEDED",requestId);
        // Withdrawal remains possible after datasource disabling, but never returns now-forbidden content.
        return new Draft(id,w.version()+1,null,false,null,null,null,List.of());}
    private static DomainException missing(){return new DomainException(404,"SEMANTIC_NOT_PUBLISHED","该主题暂无有效的已发布语义");}
    private Publication publication(SemanticStore.Release r){return new Publication(r.subjectId(),r.version(),decode(r.document()),r.digest(),r.scope(),r.reviewerId(),r.publisherId(),r.publishedAt(),r.reviewedAt());}
    /** M07 boundary. Caller identity is revalidated, and publication/current scope rechecked for every delivery. */
    public Publication retrievePublished(CurrentActor actor,String id){var current=scope(actor,id);var w=store.workspace(id);if(w==null||w.publishedVersion()==null)throw missing();var release=store.release(id,w.publishedVersion());unchanged(release.scope(),current);SemanticRules.validate(decode(release.document()),current);unchanged(release.scope(),scope(actor,id));var latest=store.workspace(id);if(latest==null||!Objects.equals(latest.publishedVersion(),w.publishedVersion()))throw missing();return publication(release);}
    public Publication published(String id){return retrievePublished(permissions.current(),id);}
    public Publication published(String id,String requestId){var actor=permissions.current();var result=retrievePublished(actor,id);audit.append(actor.id(),"SEMANTIC_READ","SUBJECT",id,"SUCCEEDED",requestId);return result;}
    public List<Publication> releases(String id){var actor=permissions.requireSuperAdmin();var current=scope(actor,id);var result=store.releases(id).stream().filter(r->r.scope().equals(SemanticRules.fingerprint(current))).map(this::publication).toList();unchanged(SemanticRules.fingerprint(current),scope(actor,id));return result;}
    public Search search(String id,String query,String requestId){if(query==null||query.length()>200)throw SemanticRules.invalid("检索词长度不能超过 200");var actor=permissions.current();var pub=retrievePublished(actor,id);String q=query.strip().toLowerCase(Locale.ROOT);var result=new ArrayList<Match>();
        for(var f:pub.document().fields())match(result,"FIELD",f.id(),f.name(),f.aliases(),q);
        for(var m:pub.document().metrics())match(result,"METRIC",m.id(),m.name(),m.aliases(),q);
        for(var d:pub.document().dimensions())match(result,"DIMENSION",d.id(),d.name(),d.aliases(),q);
        for(var e:pub.document().examples())match(result,"EXAMPLE",e.id(),e.question(),List.of(),q);
        var latest=retrievePublished(actor,id);if(latest.version()!=pub.version())throw missing();audit.append(actor.id(),"SEMANTIC_SEARCH","SUBJECT",id,"SUCCEEDED",requestId);return new Search(pub.version(),List.copyOf(result));}
    private static void match(List<Match> output,String kind,String id,String name,List<String> aliases,String q){var reasons=new ArrayList<String>();if(q.isEmpty()){output.add(new Match(kind,id,name,List.of("已发布资产")));return;}if(name.toLowerCase(Locale.ROOT).contains(q))reasons.add("名称/问题匹配");if(aliases.stream().anyMatch(a->a.toLowerCase(Locale.ROOT).contains(q)))reasons.add("别名匹配");if(!reasons.isEmpty())output.add(new Match(kind,id,name,List.copyOf(reasons)));}
}
