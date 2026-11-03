package com.datalens.chart;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import com.datalens.identity.*;
import com.datalens.permission.PermissionService;
import com.datalens.datasource.QuerySourceAccess;
import com.datalens.query.SafeSql;
import static com.datalens.chart.ChartModels.*;
/** One deny-by-default resource policy, also used by generic query endpoints. */
@Service
public class ChartPermissionService {
    private final ChartStore store;private final PermissionService permissions;private final AccountDirectory accounts;private final QuerySourceAccess sources;private final ObjectMapper json;
    public ChartPermissionService(ChartStore store,PermissionService permissions,AccountDirectory accounts,QuerySourceAccess sources,ObjectMapper json){this.store=store;this.permissions=permissions;this.accounts=accounts;this.sources=sources;this.json=json;}
    public boolean canManage(CurrentActor actor,Summary c){return c.ownerId()==actor.id()||"SUPER_ADMIN".equals(actor.role());}
    public Summary view(CurrentActor actor,String id){actor=permissions.requireActive(actor);var c=store.chart(id);
        if(c.deleted()||(!canManage(actor,c)&&!"ALL".equals(c.visibility())&&!("SPECIFIC".equals(c.visibility())&&store.shares(id).contains(actor.id()))))throw new AccessDeniedException("Chart access denied");return c;}
    public Summary manage(CurrentActor actor,String id,boolean deleted){actor=permissions.requireActive(actor);var c=store.chart(id);if(c.deleted()!=deleted||!canManage(actor,c))throw new AccessDeniedException("Chart management denied");return c;}
    public Definition definition(Summary c){return json.readValue(store.definition(c.id(),c.revisionId()),Definition.class);}
    public CurrentActor executionOwner(CurrentActor viewer,String id,String revision){var c=view(viewer,id);if(!Objects.equals(c.revisionId(),revision))throw new AccessDeniedException("Published chart revision changed");
        var owner=accounts.active(c.ownerId());validateDefinition(owner,definition(c),c.visibility(),store.shares(id));return owner;}
    public void validateDefinition(CurrentActor owner,Definition d,String visibility,List<Long> users){
        var scope=sources.authorizeSharing(owner,d.subjectId(),visibility,users);
        SafeSql.validate(d.sql(),scope.columns(),d.parameters());
    }
    public void publication(CurrentActor actor,String id,Definition d){var c=manage(actor,id,false);validateDefinition(accounts.active(c.ownerId()),d,"PRIVATE",List.of());}
    public void sharing(CurrentActor actor,String id,Sharing input){var c=manage(actor,id,false);
        if(!Set.of("PRIVATE","ALL","SPECIFIC").contains(input.visibility())||new HashSet<>(input.userIds()).size()!=input.userIds().size()||input.userIds().contains(c.ownerId()))throw ChartRules.invalid("共享范围或用户无效");
        if(input.visibility().equals("SPECIFIC")?input.userIds().isEmpty():!input.userIds().isEmpty())throw ChartRules.invalid("共享用户与范围不一致");
        // Revocation must remain possible even after the source policy or owner account is revoked.
        if(!input.visibility().equals("PRIVATE")){input.userIds().forEach(accounts::active);validateDefinition(accounts.active(c.ownerId()),definition(c),input.visibility(),input.userIds());}
    }
    public void restore(CurrentActor actor,String id){var c=manage(actor,id,true);validateDefinition(accounts.active(c.ownerId()),definition(c),"PRIVATE",List.of());}
    public void transfer(CurrentActor actor,String id,long owner){var c=manage(actor,id,false);if(!"SUPER_ADMIN".equals(actor.role()))throw new AccessDeniedException("Administrator required");validateDefinition(accounts.active(owner),definition(c),"PRIVATE",List.of());}
    public List<AccountDirectory.Recipient> recipients(){permissions.current();return accounts.recipients();}
}
