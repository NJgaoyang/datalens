package com.datalens.model;

import java.util.Objects;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.datalens.permission.PermissionService;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.model.ModelModels.*;

@Service
public class ModelConfigurationService {
    public record Runtime(Settings settings,String apiKey) {
        @Override public String toString(){return "Runtime[REDACTED]";}
    }
    private final ModelStore store;private final PermissionService permissions;private final ModelSecrets secrets;
    private final ModelEndpointPolicy endpoints;private final ObjectMapper json;private final Validator validator;private final AuditService audit;
    public ModelConfigurationService(ModelStore store,PermissionService permissions,ModelSecrets secrets,ModelEndpointPolicy endpoints,ObjectMapper json,Validator validator,AuditService audit){this.store=store;this.permissions=permissions;this.secrets=secrets;this.endpoints=endpoints;this.json=json;this.validator=validator;this.audit=audit;}
    public Settings settings(){var value=store.read();return value==null?disabled():json.readValue(value.settings(),Settings.class);}
    public Settings read(){permissions.requireSuperAdmin();return settings();}
    @Transactional public Settings save(Configure input,String requestId){
        var actor=permissions.requireSuperAdmin();if(input==null||!validator.validate(input).isEmpty())throw invalid();
        store.lock();permissions.requireSuperAdmin();var old=store.read();var current=old==null?disabled():json.readValue(old.settings(),Settings.class);
        if(current.version()!=input.expectedVersion())throw new DomainException(409,"MODEL_CONFIG_CONFLICT","模型配置已更新，请重新加载");
        if(!input.endpoint().isBlank())endpoints.validate(input.endpoint());
        String encrypted=old==null?null:old.encryptedKey();boolean newKey=input.apiKey()!=null&&!input.apiKey().isBlank();
        if(!Objects.equals(current.endpoint(),input.endpoint()))encrypted=null;
        if(newKey)encrypted=secrets.encrypt("model:1:"+input.endpoint(),input.apiKey());
        if(input.enabled()&&(input.endpoint().isBlank()||input.modelId().isBlank()||encrypted==null))throw invalid();
        var saved=new Settings(current.version()+1,input.enabled(),input.provider(),input.endpoint(),input.modelId(),input.timeoutSeconds(),input.maxInputChars(),input.maxOutputTokens(),input.maxRepairs(),input.dailyTokenBudget(),encrypted!=null);
        store.save(json.writeValueAsString(saved),encrypted);audit.append(actor.id(),"MODEL_CONFIGURE","MODEL","1","SUCCEEDED",requestId);return saved;
    }
    public Runtime runtime(){var stored=store.read();if(stored==null)throw unavailable();var s=json.readValue(stored.settings(),Settings.class);if(!s.enabled()||stored.encryptedKey()==null)throw unavailable();endpoints.validate(s.endpoint());return new Runtime(s,secrets.decrypt("model:1:"+s.endpoint(),stored.encryptedKey()));}
    public void unchanged(long version){var s=settings();if(!s.enabled()||s.version()!=version)throw new DomainException(409,"MODEL_CONFIG_CHANGED","模型配置已变化，请重试");}
    static DomainException unavailable(){return new DomainException(503,"MODEL_NOT_CONFIGURED","模型尚未配置或已停用");}
    static DomainException invalid(){return new DomainException(400,"INVALID_MODEL_CONFIG","模型配置无效，启用或修改地址时需配置有效密钥");}
}
