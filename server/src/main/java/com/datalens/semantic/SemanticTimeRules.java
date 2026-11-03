package com.datalens.semantic;

import java.time.ZoneId;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.common.web.DomainException;
import static com.datalens.semantic.SemanticModels.*;

/** Additional restriction on already validated single-table SQL; never a SQL authorization path. */
final class SemanticTimeRules {
    private SemanticTimeRules() {}
    static TimeParameters contract(Metric metric) {
        var time=metric.timeParameters();
        if(time==null)throw new DomainException(422,"SEMANTIC_TIME_UNSUPPORTED","指标尚未审核可替换的时间参数，不能执行上个月追问");
        if(time.parameterType()==null||!Set.of("DATE","TIMESTAMP").contains(time.parameterType()) ||
            time.timeZone()==null||!ZoneId.getAvailableZoneIds().contains(time.timeZone()))throw invalid();
        if(metric.parameters()==null||time.startIndex()==null||time.endIndex()==null||time.startIndex().equals(time.endIndex()) ||
            time.startIndex()<0||time.endIndex()<0||time.startIndex()>=metric.parameters().size()||time.endIndex()>=metric.parameters().size())throw invalid();
        for(int i:new int[]{time.startIndex(),time.endIndex()}) {
            var p=metric.parameters().get(i);if(p==null||!time.parameterType().equals(p.type()))throw invalid();
        }
        return time;
    }
    static void validate(Metric metric,Field timeField,String canonicalSql) {
        if(metric.timeParameters()==null)return;
        var time=contract(metric);
        try {
            // Canonical SQL is produced by SafeSql before this second parser invocation.
            // A placeholder's AST index is 1-based lexical JDBC order, not a field/type guess.
            if(!(CCJSqlParserUtil.parse(canonicalSql,p->p.withTimeOut(1000).withAllowComplexParsing(false)) instanceof PlainSelect select) ||
                !(select.getFromItem() instanceof Table table) || !identifier(table.getName()).equals(timeField.tableName()))throw invalid();
            String qualifier=table.getAlias()==null?identifier(table.getName()):identifier(table.getAlias().getName());
            boolean start=mandatoryBound(select.getWhere(),qualifier,timeField.columnName(),time.startIndex()+1,true);
            boolean end=mandatoryBound(select.getWhere(),qualifier,timeField.columnName(),time.endIndex()+1,false);
            if(!start||!end)throw invalid();
        } catch(DomainException error){throw error;}
        catch(Exception | StackOverflowError error){throw invalid();}
    }
    private static boolean mandatoryBound(Expression expression,String qualifier,String column,int position,boolean start) {
        if(expression instanceof ParenthesedExpressionList<?> parentheses && parentheses.size()==1)
            return mandatoryBound(parentheses.getFirst(),qualifier,column,position,start);
        if(expression instanceof AndExpression and)
            return mandatoryBound(and.getLeftExpression(),qualifier,column,position,start)||mandatoryBound(and.getRightExpression(),qualifier,column,position,start);
        // Only a top-level AND conjunct qualifies. Bounds hidden in OR, NOT, expressions,
        // projections, HAVING, or unrelated fields cannot acquire replacement authority.
        if(start && expression instanceof GreaterThanEquals comparison)
            return matches(comparison.getLeftExpression(),comparison.getRightExpression(),qualifier,column,position);
        if(!start && expression instanceof MinorThan comparison)
            return matches(comparison.getLeftExpression(),comparison.getRightExpression(),qualifier,column,position);
        return false;
    }
    private static boolean matches(Expression left,Expression right,String qualifier,String column,int position) {
        return left instanceof Column field && field.getTable()!=null && identifier(field.getTable().getName()).equals(qualifier) &&
            identifier(field.getColumnName()).equals(column) && right instanceof JdbcParameter parameter &&
            Integer.valueOf(position).equals(parameter.getIndex());
    }
    private static String identifier(String value) {
        return value!=null && value.startsWith("`") && value.endsWith("`")?value.substring(1,value.length()-1):value;
    }
    private static DomainException invalid(){return SemanticRules.invalid("时间参数契约无效：须绑定已审核时间字段的 >= 起始与 < 结束边界、参数位置、类型和业务时区");}
}
