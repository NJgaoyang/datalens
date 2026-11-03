package com.datalens.model;
import org.junit.jupiter.api.*;
import com.datalens.permission.PermissionService;
import com.datalens.identity.CurrentActor;
import com.datalens.semantic.SemanticService;
import com.datalens.query.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ModelQueryToolsTest {
    final PermissionService permissions=mock(PermissionService.class);final SemanticService semantics=mock(SemanticService.class);final QueryService queries=mock(QueryService.class);
    final CurrentActor actor=new CurrentActor(1,"user","USER",1);final ModelQueryTools tools=new ModelQueryTools(semantics,permissions,queries);
    @BeforeEach void setup(){when(permissions.current()).thenReturn(actor);when(permissions.requireActive(actor)).thenReturn(actor);when(semantics.retrievePublished(actor,"subject")).thenReturn(ModelFixtures.publication());}
    @Test void submitsOnlyExactServerLoadedReviewedQuery(){tools.executeReviewedMetric("subject",1,"a".repeat(64),"sales","trace");verify(queries).submitForPurpose(argThat(s->s.sql().equals("SELECT SUM(amount) AS sales FROM orders")&&s.subjectId().equals("subject")),argThat(c->c.purpose().equals("MODEL_METRIC")&&c.resourceId().equals("subject")),eq("trace"));}
    @Test void foreignOrWithdrawnRevisionDenied(){assertThatThrownBy(()->tools.executeReviewedMetric("subject",2,"a".repeat(64),"sales","trace")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(queries);}
    @Test void modelInventedMetricDenied(){assertThatThrownBy(()->tools.executeReviewedMetric("subject",1,"a".repeat(64),"invented","trace")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(queries);}
    @Test void rejectsInjectedSqlAtM03PurposeBoundary(){var context=new QueryService.QueryContext("MODEL_METRIC","subject","1:"+"a".repeat(64)+":sales");assertThatThrownBy(()->tools.validateSubmission(actor,context,new QueryModels.Submit("subject","SELECT id FROM orders",java.util.List.of(),"model_test"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);}
}
