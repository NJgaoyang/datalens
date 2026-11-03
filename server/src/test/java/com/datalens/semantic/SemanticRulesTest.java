package com.datalens.semantic;

import java.util.*;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import com.datalens.datasource.QuerySourceAccess.Scope;
import com.datalens.datasource.SourceModels.*;
import com.datalens.common.web.DomainException;
import static com.datalens.semantic.SemanticModels.*;
import static org.assertj.core.api.Assertions.*;

class SemanticRulesTest {
    final Scope scope=new Scope("test-source",1,SemanticFixtures.columns("test-source"));
    @Test void validSingleTableMetricRetainsFanoutWarning() {
        assertThat(SemanticRules.validate(SemanticFixtures.document(),scope)).anyMatch(s->s.contains("FANOUT_RISK"));
    }
    @Test void oneToManyJoinCannotBecomeAuthoritativeEvenWithDistinctSum() {
        for(String expression:List.of("SUM(o.amount)","SUM(DISTINCT o.amount)")) {
            var metric=SemanticFixtures.metric("sales","销售额","SELECT "+expression+" FROM orders o JOIN items i ON o.id=i.order_id WHERE o.created_at IS NOT NULL");
            assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(SemanticFixtures.document(),metric),scope))
                .isInstanceOf(DomainException.class).hasMessageContaining("粒度");
        }
    }
    @Test void selfJoinCannotBypassSinglePhysicalTableCount() {
        var metric=SemanticFixtures.metric("sales","销售额","SELECT SUM(o.amount) FROM orders o JOIN orders p ON o.id=p.id WHERE o.created_at IS NOT NULL");
        assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(SemanticFixtures.document(),metric),scope)).isInstanceOf(DomainException.class).hasMessageContaining("粒度");
    }
    @Test void cteAndCrossTableSubqueryCannotBypassGrainGuard() {
        for(String sql:List.of("WITH x AS (SELECT amount, created_at FROM orders) SELECT SUM(amount) FROM x WHERE created_at IS NOT NULL",
                "SELECT SUM(o.amount) * (SELECT COUNT(1) AS n FROM items) AS total FROM orders o WHERE o.created_at IS NOT NULL",
                "SELECT SUM(o.amount) * (SELECT COUNT(1) AS n FROM orders) AS total FROM orders o WHERE o.created_at IS NOT NULL",
                "SELECT SUM(o.amount) FROM orders o WHERE o.created_at IS NOT NULL AND o.id IN (SELECT order_id FROM items)")) {
            assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(SemanticFixtures.document(),SemanticFixtures.metric("sales","销售额",sql)),scope)).isInstanceOf(DomainException.class);
        }
    }
    @Test void fieldDependenciesMustMatchActualSqlAndHaveTimeField() {
        var d=SemanticFixtures.document();var m=d.metrics().getFirst();
        var missing=new Metric(m.id(),m.name(),m.aliases(),m.calculation(),m.filters(),m.timeFieldId(),m.owner(),m.unit(),m.grain(),m.nullHandling(),m.timePolicy(),List.of("amount"),m.sql(),m.parameters());
        assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(d,missing),scope)).isInstanceOf(DomainException.class);
        var extra=new Metric(m.id(),m.name(),m.aliases(),m.calculation(),m.filters(),m.timeFieldId(),m.owner(),m.unit(),m.grain(),m.nullHandling(),m.timePolicy(),List.of("amount","time","id"),m.sql(),m.parameters());
        assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(d,extra),scope)).isInstanceOf(DomainException.class);
    }
    @Test void numericFieldCannotMasqueradeAsMetricTime() {
        var d=SemanticFixtures.document();var m=d.metrics().getFirst();
        var invalid=new Metric(m.id(),m.name(),m.aliases(),m.calculation(),m.filters(),"amount",m.owner(),m.unit(),m.grain(),m.nullHandling(),m.timePolicy(),m.fieldIds(),m.sql(),m.parameters());
        assertThatThrownBy(()->SemanticRules.validate(SemanticFixtures.withMetric(d,invalid),scope)).isInstanceOf(DomainException.class).hasMessageContaining("时间字段");
    }
    @Test void missingDimensionRelationAndExampleDependenciesFailClosed() {
        var d=SemanticFixtures.document();
        var dimension=new Document(d.fields(),d.metrics(),List.of(new Dimension("dim","缺失",List.of(),"absent")),d.relations(),d.examples());
        var relation=new Document(d.fields(),d.metrics(),d.dimensions(),List.of(new Relation("rel","缺失","id","absent","UNKNOWN","订单","明细")),d.examples());
        var example=new Document(d.fields(),d.metrics(),d.dimensions(),d.relations(),List.of(new Example("ex","问题",List.of("absent"),d.metrics().getFirst().sql(),List.of(),"验证")));
        for(var invalid:List.of(dimension,relation,example))assertThatThrownBy(()->SemanticRules.validate(invalid,scope)).isInstanceOf(DomainException.class);
    }
    @Test void exampleCannotReferenceUnlistedPhysicalFields() {
        var d=SemanticFixtures.document();
        var invalid=new Document(d.fields(),d.metrics(),d.dimensions(),d.relations(),List.of(new Example("ex","问题",List.of("sales"),"SELECT id FROM orders",List.of(),"验证")));
        assertThatThrownBy(()->SemanticRules.validate(invalid,scope)).isInstanceOf(DomainException.class);
    }
    @Test void physicalTypeAndPermissionScopeAreNotInferredFromFriendlyNames() {
        var d=SemanticFixtures.document();var limited=new Scope(scope.sourceId(),1,scope.columns().stream().filter(c->!c.columnName().equals("amount")).toList());
        assertThatThrownBy(()->SemanticRules.validate(d,limited)).isInstanceOf(DomainException.class);
        var fields=new ArrayList<>(d.fields());var f=fields.get(1);fields.set(1,new BusinessField(f.id(),f.name(),f.aliases(),f.physical(),"VARCHAR",f.unit(),f.sensitivity()));
        assertThatThrownBy(()->SemanticRules.validate(new Document(fields,d.metrics(),d.dimensions(),d.relations(),d.examples()),scope)).isInstanceOf(DomainException.class);
    }
    @Test void duplicateAssetIdsAndPhysicalBindingsRejected() {
        var d=SemanticFixtures.document();var fields=new ArrayList<>(d.fields());fields.add(fields.getFirst());
        assertThatThrownBy(()->SemanticRules.validate(new Document(fields,d.metrics(),d.dimensions(),d.relations(),d.examples()),scope)).isInstanceOf(DomainException.class);
    }
    @Test void fingerprintsIgnoreMetadataOrderButTrackTypeScopeSourceAndVersion() {
        var reversed=new ArrayList<>(scope.columns());Collections.reverse(reversed);
        assertThat(SemanticRules.fingerprint(new Scope(scope.sourceId(),1,reversed))).isEqualTo(SemanticRules.fingerprint(scope));
        for(var changed:List.of(new Scope("other",1,scope.columns()),new Scope(scope.sourceId(),2,scope.columns()),new Scope(scope.sourceId(),1,scope.columns().subList(0,2))))
            assertThat(SemanticRules.fingerprint(changed)).isNotEqualTo(SemanticRules.fingerprint(scope));
        var c=scope.columns().getFirst();var columns=new ArrayList<>(scope.columns());columns.set(0,new ColumnView(c.sourceId(),c.tableName(),c.columnName(),"VARCHAR",c.ordinalPosition(),c.nullable()));
        assertThat(SemanticRules.fingerprint(new Scope(scope.sourceId(),1,columns))).isNotEqualTo(SemanticRules.fingerprint(scope));
    }
    @Test void requiredBusinessMeaningAndReviewEvidenceAreBeanValidated() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();var d=SemanticFixtures.document();var m=d.metrics().getFirst();
            assertThat(validator.validate(new Save(0,d))).isEmpty();
            var missing=new Metric(m.id(),m.name(),m.aliases(),"","",m.timeFieldId(),"",m.unit(),"","","",m.fieldIds(),m.sql(),m.parameters());
            assertThat(validator.validate(new Save(0,SemanticFixtures.withMetric(d,missing)))).hasSizeGreaterThanOrEqualTo(6);
            var e=new Example("ex","问题",List.of("sales"),m.sql(),List.of(),"");
            assertThat(validator.validate(e)).isNotEmpty();assertThat(validator.validate(new Review(1,false))).isNotEmpty();
        }
    }
    @Test void ambiguousAliasesRemainAllowedRatherThanSilentlyChoosingAuthority() {
        var d=SemanticFixtures.document();var other=SemanticFixtures.metric("sales_other","另一口径",d.metrics().getFirst().sql());
        assertThatCode(()->SemanticRules.validate(new Document(d.fields(),List.of(d.metrics().getFirst(),other),d.dimensions(),d.relations(),d.examples()),scope)).doesNotThrowAnyException();
    }
    @Test void allRiskyCardinalitiesAndMultiplePathsProduceWarnings() {
        var d=SemanticFixtures.document();
        for(String kind:List.of("ONE_TO_MANY","MANY_TO_MANY","UNKNOWN")) {
            var rel=new Relation("rel","关系","id","item_order",kind,"订单","明细");
            var doc=new Document(d.fields(),d.metrics(),d.dimensions(),List.of(rel,d.relations().getFirst()),d.examples());
            assertThat(SemanticRules.warnings(doc)).anyMatch(s->s.contains("FANOUT_RISK")).anyMatch(s->s.contains("MULTIPLE_PATHS"));
        }
    }
}
