package com.datalens.model;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import com.datalens.permission.PermissionService;
import com.datalens.identity.CurrentActor;
import com.datalens.audit.AuditService;
import static com.datalens.model.ModelModels.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ModelConfigurationServiceTest {
    final ModelStore store=mock(ModelStore.class);final PermissionService permissions=mock(PermissionService.class);final AuditService audit=mock(AuditService.class);
    final ObjectMapper json=new ObjectMapper();final jakarta.validation.ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    final ModelSecrets secrets=new ModelSecrets(java.util.Base64.getEncoder().encodeToString(new byte[32]));
    final ModelConfigurationService service=new ModelConfigurationService(store,permissions,secrets,new ModelEndpointPolicy("https://8.8.8.8/v1/chat/completions,https://1.1.1.1/v1/chat/completions"),json,factory.getValidator(),audit);
    @BeforeEach void setup(){when(permissions.requireSuperAdmin()).thenReturn(new CurrentActor(1,"admin","SUPER_ADMIN",1));}
    @AfterEach void close(){factory.close();}
    Configure input(long version,String endpoint,String key){return new Configure(version,true,"OPENAI_COMPATIBLE",endpoint,"test",30,12000,2000,1,100000,key);}
    @Test void noConfigurationReadsDisabledWithoutKey(){assertThat(service.read()).isEqualTo(disabled());}
    @Test void savesEncryptedKeyAndRedactedView(){var result=service.save(input(0,"https://8.8.8.8/v1/chat/completions","SECRET"),"trace");assertThat(result.credentialConfigured()).isTrue();assertThat(json.writeValueAsString(result)).doesNotContain("SECRET");verify(store).save(anyString(),argThat(s->s.startsWith("v1.")&&!s.contains("SECRET")));}
    @Test void endpointChangeCannotForwardExistingCredential(){var old=new Settings(1,true,"OPENAI_COMPATIBLE","https://8.8.8.8/v1/chat/completions","test",30,12000,2000,1,100000,true);when(store.read()).thenReturn(new ModelStore.Stored(json.writeValueAsString(old),"ciphertext"));assertThatThrownBy(()->service.save(input(1,"https://1.1.1.1/v1/chat/completions",null),"trace")).hasMessageContaining("密钥");verify(store,never()).save(anyString(),any());}
    @Test void invalidCredentialNeverPersists(){for(String key:java.util.List.of("x".repeat(4097),"line\nsecret","space secret")){assertThatThrownBy(()->service.save(input(0,"https://8.8.8.8/v1/chat/completions",key),"trace")).hasMessageContaining("无效");}verify(store,never()).save(anyString(),any());}
    @Test void rejectsStaleVersion(){assertThatThrownBy(()->service.save(input(1,"https://8.8.8.8/v1/chat/completions","SECRET"),"trace")).hasMessageContaining("更新");}
    @Test void deniesNonAdmin(){when(permissions.requireSuperAdmin()).thenThrow(new org.springframework.security.access.AccessDeniedException("admin only"));assertThatThrownBy(service::read).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(store);}
    @Test void secretCipherIsNonceUniqueAndBoundToEndpoint(){String a=secrets.encrypt("a","SECRET"),b=secrets.encrypt("a","SECRET");assertThat(a).isNotEqualTo(b);assertThat(secrets.decrypt("a",a)).isEqualTo("SECRET");assertThatThrownBy(()->secrets.decrypt("b",a)).hasMessageNotContaining("SECRET");}
    @Test void missingEncryptionKeyFailsOnlyOnUse(){var missing=new ModelSecrets("");assertThatThrownBy(()->missing.encrypt("a","SECRET")).hasMessageContaining("密钥");}
    @Test void credentialRecordsHaveRedactedToString(){assertThat(input(0,"https://8.8.8.8/v1/chat/completions","SECRET").toString()).doesNotContain("SECRET");assertThat(new ModelConfigurationService.Runtime(disabled(),"SECRET").toString()).doesNotContain("SECRET");}
}
