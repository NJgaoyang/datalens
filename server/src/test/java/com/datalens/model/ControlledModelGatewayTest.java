package com.datalens.model;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import com.datalens.permission.PermissionService;
import com.datalens.identity.CurrentActor;
import com.datalens.semantic.SemanticService;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.model.ModelModels.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ControlledModelGatewayTest {
    final ModelConfigurationService config=mock(ModelConfigurationService.class);
    final ModelTransport transport=mock(ModelTransport.class);
    final PermissionService permissions=mock(PermissionService.class);
    final SemanticService semantics=mock(SemanticService.class);
    final ModelStore store=mock(ModelStore.class);final AuditService audit=mock(AuditService.class);
    final CurrentActor actor=new CurrentActor(1,"user","USER",1);
    final jakarta.validation.ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    final ControlledModelGateway gateway=new ControlledModelGateway(config,transport,new ModelOutputValidator(factory.getValidator()),permissions,semantics,store,new ObjectMapper(),audit);
    final Settings settings=new Settings(1,true,"OPENAI_COMPATIBLE","https://8.8.8.8/v1/chat/completions","model",1,12000,2000,1,100000,true);
    @BeforeEach void setup(){when(permissions.current()).thenReturn(actor);when(permissions.requireActive(actor)).thenReturn(actor);when(config.runtime()).thenReturn(new ModelConfigurationService.Runtime(settings,"secret"));when(semantics.retrievePublished(actor,"subject")).thenReturn(ModelFixtures.publication());when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenReturn(ModelFixtures.valid());}
    @AfterEach void close(){factory.close();}
    @Test void producesBoundProposalWithoutSqlOrDatabaseSecrets(){var proposal=gateway.generate(new Generate("subject","Sales?"),"trace");assertThat(proposal.publicationVersion()).isEqualTo(1);verify(transport).complete(anyString(),anyString(),eq("secret"),argThat(s->!s.contains("SELECT SUM")&&!s.contains("orders")&&!s.contains("owner")&&!s.contains("secret")),anyInt(),anyInt());verify(store).finish(proposal.callId(),"SUCCEEDED",null);}
    @Test void repairsOnceOnly(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenReturn("bad",ModelFixtures.valid());gateway.generate(new Generate("subject","Sales?"),"trace");verify(transport,times(2)).complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt());verify(store,times(1)).reserve(anyString(),eq(1L),eq("subject"),eq(1L),eq(1L),anyString(),anyLong(),eq(100000L));}
    @Test void repeatedInvalidOutputStopsAfterTwoAttempts(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenReturn("bad");assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(DomainException.class);verify(transport,times(2)).complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt());verify(store).finish(anyString(),eq("FAILED"),eq("MODEL_OUTPUT_INVALID"));}
    @Test void timeoutDoesNotRetryOrRefund(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenThrow(new DomainException(504,"MODEL_TIMEOUT","timeout"));assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(DomainException.class);verify(transport,times(1)).complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt());verify(store).finish(anyString(),eq("FAILED"),eq("MODEL_TIMEOUT"));}
    @Test void disabledConfigurationNeverContactsProvider(){when(config.runtime()).thenThrow(ModelConfigurationService.unavailable());assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(DomainException.class);verifyNoInteractions(transport,store,semantics);}
    @Test void publicationChangeDuringCallBlocksDelivery(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenAnswer(a->{when(semantics.retrievePublished(actor,"subject")).thenReturn(ModelFixtures.publication("PUBLIC",2));return ModelFixtures.valid();});assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).hasMessageContaining("变化");verify(store).finish(anyString(),eq("FAILED"),eq("MODEL_SEMANTIC_CHANGED"));}
    @Test void revokedAccessNeverTriggersRepair(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenAnswer(a->{when(permissions.requireActive(actor)).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));return "bad";});assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verify(transport,times(1)).complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt());}
    @Test void revocationWhilePersistingSuccessBlocksDelivery(){doAnswer(a->{when(permissions.requireActive(actor)).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));return null;}).when(store).finish(anyString(),eq("SUCCEEDED"),isNull());assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void revocationDuringAuditBlocksDelivery(){doAnswer(a->{when(permissions.requireActive(actor)).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));return null;}).when(audit).append(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString());assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
    @Test void budgetDenialStopsTransmission(){doThrow(new DomainException(429,"MODEL_BUDGET_EXCEEDED","budget")).when(store).reserve(anyString(),anyLong(),anyString(),anyLong(),anyLong(),anyString(),anyLong(),anyLong());assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).isInstanceOf(DomainException.class);verifyNoInteractions(transport);}
    @Test void genericProviderErrorIsSanitized(){when(transport.complete(anyString(),anyString(),anyString(),anyString(),anyInt(),anyInt())).thenThrow(new RuntimeException("SECRET upstream body"));assertThatThrownBy(()->gateway.generate(new Generate("subject","Sales?"),"trace")).hasMessageNotContaining("SECRET").hasMessageNotContaining("upstream");}
}
