package com.datalens.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.datalens.common.web.DomainException;
import com.datalens.datasource.QuerySourceAccess.Scope;
import com.datalens.datasource.SourceModels.Field;
import com.datalens.query.SafeSql;
import com.datalens.query.QueryParameters;
import com.datalens.query.QueryModels.Parameter;
import static com.datalens.semantic.SemanticModels.*;

/** Semantic facts are human assertions, not proofs of business correctness. */
public final class SemanticRules {
    private SemanticRules() {}
    public static DomainException invalid(String message){return new DomainException(422,"SEMANTIC_INVALID",message);}
    public static String digest(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String fingerprint(Scope scope) {
        var columns=scope.columns().stream().map(c->c.tableName()+"\u001f"+c.columnName()+"\u001f"+c.typeName()+"\u001f"+c.nullable()+"\u001f"+c.ordinalPosition()).sorted().toList();
        return digest(scope.sourceId()+"\n"+scope.sourceVersion()+"\n"+String.join("\n",columns));
    }
    public static List<String> validate(Document doc,Scope scope) {
        if(doc==null || doc.fields()==null || doc.metrics()==null || doc.dimensions()==null || doc.relations()==null || doc.examples()==null)throw invalid("语义文档不完整");
        var ids=new HashSet<String>();var fields=new LinkedHashMap<String,BusinessField>();var physical=new HashSet<Field>();
        for(var f:doc.fields()) {
            unique(ids,f.id());fields.put(f.id(),f);
            if(!physical.add(f.physical()))throw invalid("同一物理字段不能重复定义");
            var column=scope.columns().stream().filter(c->c.tableName().equals(f.physical().tableName())&&c.columnName().equals(f.physical().columnName())).findFirst().orElseThrow(()->invalid("字段不在当前主题授权范围内"));
            if(!column.typeName().equalsIgnoreCase(f.type()))throw invalid("业务字段类型与元数据不一致");
        }
        var metrics=new LinkedHashMap<String,Metric>();
        for(var m:doc.metrics()) {
            unique(ids,m.id());metrics.put(m.id(),m);var dependencies=dependencies(fields,m.fieldIds());
            if(!m.fieldIds().contains(m.timeFieldId()))throw invalid("指标时间字段必须包含于字段依赖");
            String timeType=fields.get(m.timeFieldId()).type().toUpperCase(Locale.ROOT);
            if(!timeType.matches("(DATE|DATETIME|TIMESTAMP)(\\([0-9]+\\))?"))throw invalid("指标时间字段必须是 DATE、DATETIME 或 TIMESTAMP 类型");
            var referenced=query(m.sql(),m.parameters(),scope);
            if(!dependencies.equals(referenced))throw invalid("指标字段依赖必须与查询实际引用一致");
        }
        for(var d:doc.dimensions()){unique(ids,d.id());dependencies(fields,List.of(d.fieldId()));}
        var edges=new HashSet<String>();
        for(var r:doc.relations()){
            unique(ids,r.id());dependencies(fields,List.of(r.leftFieldId(),r.rightFieldId()));
            if(r.leftFieldId().equals(r.rightFieldId()))throw invalid("关系两端不能是同一字段");
            String edge=java.util.stream.Stream.of(r.leftFieldId(),r.rightFieldId()).sorted().reduce((a,b)->a+":"+b).orElseThrow();
            if(!edges.add(edge))throw invalid("重复关系路径");
        }
        for(var e:doc.examples()){
            unique(ids,e.id());var dependencies=new HashSet<Field>();
            for(String id:e.metricIds()){var m=metrics.get(id);if(m==null)throw invalid("例题引用不存在的指标");dependencies.addAll(dependencies(fields,m.fieldIds()));}
            if(!dependencies.containsAll(query(e.sql(),e.parameters(),scope)))throw invalid("例题引用了指标依赖之外的字段");
        }
        return warnings(doc);
    }
    private static void unique(Set<String> ids,String id){if(id==null||!ids.add(id))throw invalid("资产 ID 必须唯一");}
    private static Set<Field> dependencies(Map<String,BusinessField> fields,List<String> ids){var refs=new HashSet<Field>();for(String id:ids){var f=fields.get(id);if(f==null)throw invalid("资产引用不存在的字段");refs.add(f.physical());}return refs;}
    private static Set<Field> query(String sql,List<Parameter> parameters,Scope scope) {
        for(var p:parameters)QueryParameters.value(p);
        var safe=SafeSql.validate(sql,scope.columns(),parameters);
        // Rejection only, after complete AST validation. Until a grain-aware SQL compiler exists,
        // ALL joins (including self-joins) and multi-table definitions are refused, even claimed 1:1.
        String normalized=safe.sql().toUpperCase(Locale.ROOT);
        if(safe.fields().stream().map(Field::tableName).distinct().count()!=1 || normalized.contains(" JOIN ") || normalized.contains("WITH ") || normalized.indexOf("SELECT ")!=normalized.lastIndexOf("SELECT "))
            throw new DomainException(422,"SEMANTIC_JOIN_RISK","跨表、关联、子查询或 CTE 查询尚无粒度证明，不能发布为权威口径；请提供单表预聚合视图");
        return safe.fields();
    }
    public static List<String> warnings(Document doc) {
        var warnings=new ArrayList<String>();
        for(var r:doc.relations()) {
            if(Set.of("ONE_TO_MANY","MANY_TO_MANY","UNKNOWN").contains(r.cardinality()))warnings.add(r.id()+": FANOUT_RISK，可能重复累计；不得直接跨粒度求和");
            else warnings.add(r.id()+": 关系基数是人工声明，尚未验证唯一性；反向关联仍可能重复累计");
        }
        if(doc.relations().size()>1)warnings.add("MULTIPLE_PATHS：多关系路径可能产生重复累计或歧义");
        if(doc.examples().size()<20||doc.examples().size()>30)warnings.add("首批业务验收建议准备 20–30 个真实问题；软件校验不代替人工确认");
        warnings.add("SQL 仅做静态安全验证，未执行例题，也未验证真实数据或业务正确性");
        return List.copyOf(warnings);
    }
}
