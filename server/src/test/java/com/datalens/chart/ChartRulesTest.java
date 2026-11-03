package com.datalens.chart;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.datalens.chart.ChartModels.*;
import static com.datalens.query.QueryModels.*;
class ChartRulesTest {
    Definition dates(TimeRule rule){return new Definition("s","SELECT amount FROM orders WHERE created >= ? AND created < ?",List.of(new Parameter("DATE","2020-01-01"),new Parameter("DATE","2020-01-02")),rule,"实付金额",new Spec("KPI",null,List.of("amount")));}
    @Test void businessTimezoneCrossesUtcMidnightAndLeapDay(){var d=dates(new TimeRule("LAST_DAYS",30,"Asia/Shanghai",0,1));var p=ChartRules.resolve(d,Clock.fixed(Instant.parse("2024-02-29T17:00:00Z"),ZoneOffset.UTC));assertThat(p).containsExactly(new Parameter("DATE","2024-02-01"),new Parameter("DATE","2024-03-02"));assertThat(d.parameters().getFirst().value()).isEqualTo("2020-01-01");}
    @Test void daylightSavingUsesCalendarDaysNot24HourIntervals(){var d=dates(new TimeRule("LAST_DAYS",2,"America/New_York",0,1));assertThat(ChartRules.resolve(d,Clock.fixed(Instant.parse("2026-03-09T03:30:00Z"),ZoneOffset.UTC))).containsExactly(new Parameter("DATE","2026-03-07"),new Parameter("DATE","2026-03-09"));}
    @Test void invalidDatesAndSlotsFailClosed(){for(var rule:List.of(new TimeRule("LAST_DAYS",0,"UTC",0,1),new TimeRule("LAST_DAYS",30,"fake/zone",0,1),new TimeRule("LAST_DAYS",30,"UTC",0,0),new TimeRule("LAST_DAYS",30,"UTC",0,2),new TimeRule("NONE",30,null,null,null)))assertThatThrownBy(()->ChartRules.resolve(dates(rule),Clock.systemUTC())).hasMessageContaining("日期").isInstanceOf(com.datalens.common.web.DomainException.class);}
    Result rows(boolean truncated,String... values){var rows=new ArrayList<List<String>>();for(String v:values)rows.add(Arrays.asList("<img onerror=evil>",v));return new Result(List.of(new Column("label","VARCHAR"),new Column("amount","DECIMAL")),rows,truncated,1000);}
    @Test void exactAmountsBigIntegersNullAndZeroAreNeverAggregatedOrRewritten(){for(String value:Arrays.asList("9007199254740993.1200","0",null)){var r=rows(false,value);ChartRules.validate(new Spec("KPI",null,List.of("amount")),r);assertThat(r.rows().getFirst().get(1)).isEqualTo(value);}}
    @Test void tableDefaultDisplaysAllColumns(){ChartRules.validate(new Spec("TABLE",null,List.of()),rows(false,"1"));}
    @Test void fiveSupportedTypesAndEmptyData(){for(String type:List.of("TABLE","KPI","BAR","LINE","PIE"))ChartRules.validate(new Spec(type,Set.of("TABLE","KPI").contains(type)?null:"label",List.of("amount")),rows(false));}
    @Test void kpiRejectsMultipleRowsAndTruncation(){assertThatThrownBy(()->ChartRules.validate(new Spec("KPI",null,List.of("amount")),rows(false,"1","2"))).hasMessageContaining("完整");assertThatThrownBy(()->ChartRules.validate(new Spec("KPI",null,List.of("amount")),rows(true,"1"))).hasMessageContaining("完整");}
    @Test void pieRejectsTruncatedAndNegativeInput(){for(Result r:List.of(rows(true,"1"),rows(false,"-1")))assertThatThrownBy(()->ChartRules.validate(new Spec("PIE","label",List.of("amount")),r)).isInstanceOf(com.datalens.common.web.DomainException.class);}
    @Test void plottedNonfiniteIsRejectedButExactTableAndKpiRemainAvailable(){var huge=rows(false,"1E999");for(String type:List.of("BAR","LINE","PIE"))assertThatThrownBy(()->ChartRules.validate(new Spec(type,"label",List.of("amount")),huge)).hasMessageContaining("范围");ChartRules.validate(new Spec("TABLE",null,List.of("amount")),huge);ChartRules.validate(new Spec("KPI",null,List.of("amount")),huge);}
    @Test void pieNullAndPlotUnderflowAreRejected(){assertThatThrownBy(()->ChartRules.validate(new Spec("PIE","label",List.of("amount")),rows(false,(String)null))).hasMessageContaining("空值");assertThatThrownBy(()->ChartRules.validate(new Spec("BAR","label",List.of("amount")),rows(false,"1e-999"))).hasMessageContaining("范围");}
    @Test void schemaDriftAndDuplicateMappingsReject(){for(Spec spec:List.of(new Spec("BAR","missing",List.of("amount")),new Spec("TABLE",null,List.of("missing")),new Spec("TABLE",null,List.of("amount","amount")),new Spec("BAR","amount",List.of("amount")),new Spec("KPI",null,List.of("label"))))assertThatThrownBy(()->ChartRules.validate(spec,rows(false,"1"))).isInstanceOf(com.datalens.common.web.DomainException.class);}
}
