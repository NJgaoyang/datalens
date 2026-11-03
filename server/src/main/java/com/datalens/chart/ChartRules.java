package com.datalens.chart;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.datalens.common.web.DomainException;
import com.datalens.query.QueryModels.*;
import static com.datalens.chart.ChartModels.*;

/** No ECharts options, callbacks, HTML or executable code enter the stored specification. */
public final class ChartRules {
    private ChartRules() {}
    static DomainException invalid(String message){return new DomainException(422,"CHART_INVALID",message);}
    public static List<Parameter> resolve(Definition d,Clock clock) {
        var rule=d.timeRule();var params=new ArrayList<>(d.parameters());
        if(rule.kind().equals("NONE")) {
            if(rule.days()!=null||rule.timeZone()!=null||rule.startIndex()!=null||rule.endIndex()!=null)throw invalid("固定日期规则不能包含相对日期设置");
            return List.copyOf(params);
        }
        if(!rule.kind().equals("LAST_DAYS")||rule.days()==null||rule.days()<1||rule.days()>3660||rule.timeZone()==null||!ZoneId.getAvailableZoneIds().contains(rule.timeZone()))throw invalid("业务时区或日期范围无效");
        if(rule.startIndex()==null||rule.endIndex()==null||rule.startIndex().equals(rule.endIndex())||rule.startIndex()<0||rule.endIndex()<0||rule.startIndex()>=params.size()||rule.endIndex()>=params.size())throw invalid("日期参数位置无效");
        if(!params.get(rule.startIndex()).type().equals("DATE")||!params.get(rule.endIndex()).type().equals("DATE"))throw invalid("相对日期参数必须是 DATE");
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of(rule.timeZone())));
        params.set(rule.startIndex(),new Parameter("DATE",today.minusDays(rule.days()-1L).toString()));
        params.set(rule.endIndex(),new Parameter("DATE",today.plusDays(1).toString()));
        return List.copyOf(params);
    }
    public static void validate(Spec spec,Result result) {
        Set<String> fields=new HashSet<>();for(Column c:result.columns())if(!fields.add(c.name()))throw invalid("输出字段名重复");
        if((!spec.type().equals("TABLE")&&spec.measures().isEmpty())||spec.measures().size()>20||new HashSet<>(spec.measures()).size()!=spec.measures().size()||!fields.containsAll(spec.measures()))throw invalid("请选择有效且不重复的字段");
        if(!Set.of("TABLE","KPI","BAR","LINE","PIE").contains(spec.type()))throw invalid("图表类型无效");
        if(spec.type().equals("TABLE")) {if(spec.dimension()!=null)throw invalid("表格不使用维度映射");return;}
        if(spec.type().equals("KPI")) {
            if(spec.dimension()!=null||spec.measures().size()!=1||result.rows().size()>1||result.truncated())throw invalid("指标卡必须使用至多一行的完整聚合查询结果");
        } else if(spec.dimension()==null||!fields.contains(spec.dimension())||spec.measures().contains(spec.dimension()))throw invalid("维度字段无效");
        if(spec.type().equals("PIE")&&(spec.measures().size()!=1||result.truncated()))throw invalid("饼图必须使用未截断的单指标结果");
        for(String measure:spec.measures()) {
            int index=-1;for(int i=0;i<result.columns().size();i++)if(result.columns().get(i).name().equals(measure))index=i;
            String type=result.columns().get(index).jdbcType().toUpperCase(Locale.ROOT);
            if(!type.matches(".*(INT|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL|NUMBER).*"))throw invalid("指标字段必须是数值类型");
            for(var row:result.rows()) {
                String raw=row.get(index);if(raw==null){if(spec.type().equals("PIE"))throw invalid("饼图不支持空值");continue;}
                try {BigDecimal number=new BigDecimal(raw);if(spec.type().equals("PIE")&&number.signum()<0)throw invalid("饼图不支持负数");
                    if(!spec.type().equals("KPI")&&(!Double.isFinite(number.doubleValue())||(number.signum()!=0&&number.doubleValue()==0)))throw invalid("数值超过绘图范围，请使用表格");
                }catch(NumberFormatException bad){throw invalid("数值字段格式无效");}
            }
        }
    }
}
