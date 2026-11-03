package com.datalens.semantic;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import com.datalens.datasource.QuerySourceAccess;
import com.datalens.audit.AuditService;
import com.datalens.common.web.DomainException;
import static com.datalens.semantic.SemanticModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticServiceTest {
    final SemanticStore store=mock(SemanticStore.class);
    final PermissionService permissions=mock(PermissionService.class);
    final QuerySourceAccess sources=mock(QuerySourceAccess.class);
    final AuditService audit=mock(AuditService.class);
    final ObjectMapper json=new ObjectMapper();
    final ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    final CurrentActor actor=new CurrentActor(7,"reviewer","SUPER_ADMIN",1);
    final QuerySourceAccess.Scope scope=new QuerySourceAccess.Scope("source",1,SemanticFixtures.columns("source"));
    final SemanticService service=new SemanticService(store,permissions,sources,json,factory.getValidator(),audit);
    SemanticStore.Workspace workspace;
    @BeforeEach void setup() {
        when(permissions.current()).thenReturn(actor);when(permissions.requireSuperAdmin()).thenReturn(actor);
        when(sources.authorize(actor,"subject")).thenReturn(scope);
        seed(SemanticFixtures.document());
    }
    void seed(Document doc) {
        String text=json.writeValueAsString(doc),digest=SemanticRules.digest(text),fingerprint=SemanticRules.fingerprint(scope);
        workspace=new SemanticStore.Workspace("subject",3,text,digest,fingerprint,digest,actor.id(),Instant.EPOCH,1L,2);
        when(store.workspace("subject")).thenReturn(workspace);
        when(store.release("subject",1)).thenReturn(new SemanticStore.Release("subject",1,text,digest,fingerprint,actor.id(),actor.id(),Instant.EPOCH,Instant.EPOCH));
    }
    @AfterEach void close(){factory.close();}
    @Test void deliveryReauthorizesAfterLoadingPublication() {
        when(sources.authorize(actor,"subject")).thenReturn(scope).thenThrow(new AccessDeniedException("revoked"));
        assertThatThrownBy(()->service.published("subject")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void withdrawalDuringReadDeniesPreviouslyLoadedSnapshot() {
        var withdrawn=new SemanticStore.Workspace(workspace.subjectId(),4,workspace.document(),workspace.digest(),workspace.scope(),workspace.reviewedDigest(),workspace.reviewerId(),workspace.reviewedAt(),null,2);
        when(store.workspace("subject")).thenReturn(workspace,withdrawn);
        assertThatThrownBy(()->service.published("subject")).isInstanceOf(DomainException.class);
    }
    @Test void metadataChangeDuringReadDeniesPreviouslyLoadedSnapshot() {
        when(sources.authorize(actor,"subject")).thenReturn(scope).thenReturn(new QuerySourceAccess.Scope("source",2,scope.columns()));
        assertThatThrownBy(()->service.published("subject")).isInstanceOf(DomainException.class).hasMessageContaining("变化");
    }
    @Test void changedDigestCannotBePublishedUsingOldReview() {
        var changed=new SemanticStore.Workspace(workspace.subjectId(),3,workspace.document(),workspace.digest(),workspace.scope(),"different",workspace.reviewerId(),workspace.reviewedAt(),1L,2);
        when(store.workspace("subject")).thenReturn(changed);
        assertThatThrownBy(()->service.publish("subject",new Version(3),"trace")).isInstanceOf(DomainException.class);
        verify(store,never()).publish(any(),anyLong());
    }
    @Test void adminRevocationWhileWaitingForMutationLockStopsPublication() {
        when(permissions.requireSuperAdmin()).thenReturn(actor).thenThrow(new AccessDeniedException("role changed"));
        assertThatThrownBy(()->service.publish("subject",new Version(3),"trace")).isInstanceOf(AccessDeniedException.class);
        verify(store,never()).publish(any(),anyLong());
    }
    @Test void explicitFalseReviewIsNeverStored() {
        assertThatThrownBy(()->service.review("subject",new Review(3,false),"trace")).isInstanceOf(DomainException.class);
        verify(store,never()).review(anyString(),anyLong());
    }
    @Test void invalidTimeReplacementCannotBeReviewedPublishedOrRetrieved() {
        var metric=SemanticTimeRulesTest.metric(SemanticTimeRulesTest.sql(),SemanticTimeRulesTest.dates(),new TimeParameters(1,0,"UTC","DATE"));
        seed(SemanticFixtures.withMetric(SemanticFixtures.document(),metric));
        assertThatThrownBy(()->service.review("subject",new Review(3,true),"trace")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->service.publish("subject",new Version(3),"trace")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->service.published("subject")).isInstanceOf(DomainException.class);
        verify(store,never()).review(anyString(),anyLong());verify(store,never()).publish(any(),anyLong());
    }
    @Test void ambiguousAliasSearchKeepsBothCandidatesAndActualActorAudit() {
        var d=SemanticFixtures.document();seed(new Document(d.fields(),List.of(d.metrics().getFirst(),SemanticFixtures.metric("sales_other","另一口径",d.metrics().getFirst().sql())),d.dimensions(),d.relations(),d.examples()));
        var found=service.search("subject","营收","trace");
        assertThat(found.matches()).extracting(Match::id).containsExactly("sales","sales_other");
        assertThat(found.publicationVersion()).isEqualTo(1);
        verify(audit).append(actor.id(),"SEMANTIC_SEARCH","SUBJECT","subject","SUCCEEDED","trace");
    }
    @Test void noMatchDoesNotInventMetric() {assertThat(service.search("subject","nothing matches this","trace").matches()).isEmpty();}
    @Test void examplesAreSearchableOnlyFromPublishedSnapshot() {
        var found=service.search("subject","销售额是多少","trace");
        assertThat(found.matches()).extracting(Match::kind).containsExactly("EXAMPLE");
    }
    @Test void scopeChangedDraftReturnsVersionButNotForbiddenContents() {
        when(sources.authorize(actor,"subject")).thenReturn(new QuerySourceAccess.Scope("source",2,scope.columns()));
        var draft=service.draft("subject");
        assertThat(draft.version()).isEqualTo(3);assertThat(draft.document()).isNull();assertThat(draft.reviewed()).isFalse();
    }
    @Test void draftFinalScopeReductionNeverReturnsOldDocument() {
        when(sources.authorize(actor,"subject")).thenReturn(scope).thenReturn(new QuerySourceAccess.Scope("source",1,scope.columns().subList(0,1)));
        try { assertThat(service.draft("subject").document()).isNull(); }
        catch(DomainException | AccessDeniedException safeFailure) { /* fail closed is valid */ }
    }
    @Test void disabledSourceDraftExposesOnlyRecoveryVersion() {
        when(sources.authorize(actor,"subject")).thenThrow(new DomainException(409,"SOURCE_DISABLED","disabled"));
        var draft=service.draft("subject");
        assertThat(draft.version()).isEqualTo(3);assertThat(draft.document()).isNull();
        assertThat(draft.reviewed()).isFalse();assertThat(draft.reviewerId()).isNull();assertThat(draft.reviewedAt()).isNull();
        service.withdraw("subject",new Version(draft.version()),"trace");verify(store).withdraw("subject");
    }
    @Test void zeroEffectiveFieldsDraftExposesOnlyRecoveryVersion() {
        when(sources.authorize(actor,"subject")).thenThrow(new AccessDeniedException("No allowed fields"));
        var draft=service.draft("subject");assertThat(draft.version()).isEqualTo(3);assertThat(draft.document()).isNull();
        service.withdraw("subject",new Version(draft.version()),"trace");verify(store).withdraw("subject");
    }
    @Test void adminRoleRecheckedBeforeRedactedRecoveryStateIsDisclosed() {
        when(sources.authorize(actor,"subject")).thenThrow(new AccessDeniedException("No allowed fields"));
        when(permissions.requireSuperAdmin()).thenReturn(actor).thenThrow(new AccessDeniedException("Role revoked"));
        assertThatThrownBy(()->service.draft("subject")).isInstanceOf(AccessDeniedException.class).hasMessage("Role revoked");
    }
    @Test void authenticationFailureNeverBecomesSuccessfulRecoveryMetadata() {
        when(sources.authorize(actor,"subject")).thenThrow(new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Disabled account"));
        assertThatThrownBy(()->service.draft("subject")).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }
    @Test void withdrawalWorksAfterDatasourceDisabledWithoutReturningOldContents() {
        when(sources.authorize(actor,"subject")).thenThrow(new DomainException(409,"SOURCE_DISABLED","disabled"));
        var withdrawn=service.withdraw("subject",new Version(3),"trace");
        assertThat(withdrawn.document()).isNull();assertThat(withdrawn.publishedVersion()).isNull();
        verify(store).withdraw("subject");verify(audit).append(actor.id(),"SEMANTIC_WITHDRAW","SUBJECT","subject","SUCCEEDED","trace");
    }
}
