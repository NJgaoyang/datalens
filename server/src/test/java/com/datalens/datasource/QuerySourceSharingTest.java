package com.datalens.datasource;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import com.datalens.identity.CurrentActor;
import com.datalens.permission.PermissionService;
import static com.datalens.datasource.SourceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuerySourceSharingTest {
    final CurrentActor owner=new CurrentActor(1,"owner","USER",1);
    SourceRepository repository; PermissionService permissions; QuerySourceAccess access;
    @BeforeEach void setup() {
        repository=mock(SourceRepository.class);permissions=mock(PermissionService.class);
        access=new QuerySourceAccess(repository,permissions,mock(SourcePools.class));
        when(permissions.requireActive(owner)).thenReturn(owner);
        var source=new SourceView("source","source","127.0.0.1",9030,"catalog","database","reader",true,List.of("orders"),1,"OK",null,null);
        when(repository.source("source",false)).thenReturn(new StoredSource(source,"hidden"));
        when(repository.columns("source")).thenReturn(List.of(new ColumnView("source","orders","amount","DECIMAL",1,false)));
    }
    void policy(String policy,List<Long> members) {
        when(repository.subject("subject")).thenReturn(new SubjectView("subject","subject","source",List.of(new Field("orders","amount")),List.of(),members,policy));
    }
    @Test void noneAllowsPrivateButRejectsAllSharing() {
        policy("NONE",List.of(1L));
        assertThat(access.authorizeSharing(owner,"subject","PRIVATE",List.of()).sourceId()).isEqualTo("source");
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","ALL",List.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","SPECIFIC",List.of(2L))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void authorizedUsersPolicyRestrictsRecipientsAndCannotBecomeAll() {
        policy("AUTHORIZED_USERS",List.of(1L,2L));
        assertThat(access.authorizeSharing(owner,"subject","SPECIFIC",List.of(2L))).isNotNull();
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","SPECIFIC",List.of(3L))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","ALL",List.of())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void explicitAllPolicyAllowsSharedViewersWithoutSubjectMembership() {
        policy("ALL_AUTHENTICATED",List.of(1L));
        assertThat(access.authorizeSharing(owner,"subject","ALL",List.of())).isNotNull();
        assertThat(access.authorizeSharing(owner,"subject","SPECIFIC",List.of(2L))).isNotNull();
        var viewer=new CurrentActor(2,"viewer","USER",1);
        when(permissions.requireActive(viewer)).thenReturn(viewer);
        assertThatThrownBy(()->access.authorize(viewer,"subject")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void ownerRevocationAlwaysStopsSharedCapability() {
        policy("ALL_AUTHENTICATED",List.of(2L));
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","ALL",List.of())).isInstanceOf(AccessDeniedException.class);
        policy("ALL_AUTHENTICATED",List.of(1L));
        when(permissions.requireActive(owner)).thenThrow(new AccessDeniedException("Inactive owner"));
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","ALL",List.of())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void malformedVisibilityAndRecipientShapesFailClosed() {
        policy("ALL_AUTHENTICATED",List.of(1L));
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","PUBLIC",List.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","SPECIFIC",List.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","ALL",List.of(2L))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->access.authorizeSharing(owner,"subject","PRIVATE",List.of(2L))).isInstanceOf(AccessDeniedException.class);
    }
}
