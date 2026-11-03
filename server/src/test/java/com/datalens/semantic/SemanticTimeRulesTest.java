package com.datalens.semantic;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.datalens.datasource.QuerySourceAccess.Scope;
import com.datalens.common.web.DomainException;
import com.datalens.query.QueryModels.Parameter;
import static com.datalens.semantic.SemanticModels.*;
import static org.assertj.core.api.Assertions.*;

class SemanticTimeRulesTest {
    final Scope scope=new Scope("source",1,SemanticFixtures.columns("source"));
    static Metric metric(String sql,List<Parameter> parameters,TimeParameters contract) {
        var m=SemanticFixtures.document().metrics().getFirst();
        return new Metric(m.id(),m.name(),m.aliases(),m.calculation(),m.filters(),m.timeFieldId(),m.owner(),m.unit(),m.grain(),m.nullHandling(),m.timePolicy(),m.fieldIds(),sql,parameters,contract);
    }
    static List<Parameter> dates(){return List.of(new Parameter("DATE","2024-01-01"),new Parameter("DATE","2024-02-01"));}
    static String sql(){return "SELECT SUM(o.amount) AS sales FROM orders o WHERE o.created_at >= ? AND o.created_at < ?";}
    void validate(Metric metric){SemanticRules.validate(SemanticFixtures.withMetric(SemanticFixtures.document(),metric),scope);}
    @Test void reviewedRangeUsesExplicitZeroBasedSlotsWithoutChangingSqlOrFixedParameters() {
        var parameters=List.of(new Parameter("DECIMAL","9"),new Parameter("DATE","2024-01-01"),new Parameter("DATE","2024-02-01"));
        String sql="SELECT SUM(amount) + ? AS sales FROM orders WHERE created_at >= ? AND created_at < ?";
        var m=metric(sql,parameters,new TimeParameters(1,2,"Asia/Shanghai","DATE"));
        validate(m);var rule=SemanticRules.lastMonthRule(m);
        assertThat(rule.kind()).isEqualTo("LAST_MONTH");assertThat(rule.days()).isNull();assertThat(rule.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(rule.startIndex()).isEqualTo(1);assertThat(rule.endIndex()).isEqualTo(2);
        assertThat(m.sql()).isEqualTo(sql);assertThat(m.parameters()).containsExactlyElementsOf(parameters);
    }
    @Test void startAndEndNeedNotBeAdjacentOrInIndexOrder() {
        var m=metric("SELECT SUM(amount) AS sales FROM orders WHERE created_at < ? AND amount > ? AND created_at >= ?",
            List.of(dates().get(1),new Parameter("INTEGER","10"),dates().get(0)),new TimeParameters(2,0,"UTC","DATE"));
        assertThatCode(()->validate(m)).doesNotThrowAnyException();
    }
    @Test void timestampContractAcceptsExplicitInstantValues() {
        var m=metric(sql(),List.of(new Parameter("TIMESTAMP","2024-03-01T05:00:00Z"),new Parameter("TIMESTAMP","2024-04-01T04:00:00Z")),new TimeParameters(0,1,"America/New_York","TIMESTAMP"));
        assertThatCode(()->validate(m)).doesNotThrowAnyException();
    }
    @Test void legacyDocumentsDeserializeWithoutGrantingTimeSubstitution() {
        var original=SemanticFixtures.document().metrics().getFirst();
        var json=new ObjectMapper();var old=json.writeValueAsString(original).replace(",\"timeParameters\":null","");
        var decoded=json.readValue(old,Metric.class);assertThat(decoded.timeParameters()).isNull();validate(decoded);
        assertThat(json.writeValueAsString(decoded)).isEqualTo(old);
        assertThatThrownBy(()->SemanticRules.lastMonthRule(decoded)).isInstanceOfSatisfying(DomainException.class,error->assertThat(error.code()).isEqualTo("SEMANTIC_TIME_UNSUPPORTED"));
    }
    @Test void invalidSlotsTypesAndTimezoneFailClosed() {
        for(var contract:List.of(new TimeParameters(-1,1,"UTC","DATE"),new TimeParameters(0,2,"UTC","DATE"),new TimeParameters(0,0,"UTC","DATE"),
            new TimeParameters(0,1,"bad/zone","DATE"),new TimeParameters(0,1,"UTC","STRING"),new TimeParameters(0,1,"UTC","TIMESTAMP"),
            new TimeParameters(null,1,"UTC","DATE"),new TimeParameters(0,1,null,"DATE"),new TimeParameters(0,1,"UTC",null))) {
            assertThatThrownBy(()->validate(metric(sql(),dates(),contract))).isInstanceOf(DomainException.class).hasMessageContaining("时间参数");
        }
    }
    @Test void onlyMandatoryHalfOpenBoundsOnApprovedTimeFieldMayChange() {
        for(String sql:List.of(
            "SELECT SUM(amount) FROM orders WHERE amount >= ? AND created_at < ?",
            "SELECT SUM(amount) FROM orders WHERE created_at >= ? AND amount < ?",
            "SELECT SUM(amount) FROM orders WHERE created_at > ? AND created_at <= ?",
            "SELECT SUM(amount) FROM orders WHERE created_at >= ? OR created_at < ?",
            "SELECT SUM(amount) FROM orders WHERE created_at >= ? AND (created_at < ? OR amount > 0)",
            "SELECT SUM(amount) FROM orders WHERE created_at BETWEEN ? AND ?",
            "SELECT SUM(amount) FROM orders WHERE created_at + 1 >= ? AND created_at < ?",
            "SELECT SUM(amount) FROM orders WHERE created_at IS NOT NULL HAVING MIN(created_at) >= ? AND MAX(created_at) < ?")) {
            assertThatThrownBy(()->validate(metric(sql,dates(),new TimeParameters(0,1,"UTC","DATE"))))
                .as(sql).isInstanceOf(DomainException.class);
        }
    }
    @Test void swappingSlotsCannotReverseTheApprovedPeriod() {
        assertThatThrownBy(()->validate(metric(sql(),dates(),new TimeParameters(1,0,"UTC","DATE")))).isInstanceOf(DomainException.class);
    }
    @Test void unapprovedSqlParameterCannotBeSelectedJustBecauseItHasDateType() {
        var m=metric("SELECT SUM(amount) + ? FROM orders WHERE created_at >= ? AND created_at < ?",
            List.of(dates().get(0),dates().get(0),dates().get(1)),new TimeParameters(0,2,"UTC","DATE"));
        assertThatThrownBy(()->validate(m)).isInstanceOf(DomainException.class);
    }
}
