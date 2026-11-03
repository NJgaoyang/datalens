package com.datalens.model;

import java.util.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Column;
import com.datalens.semantic.SemanticModels.*;
import com.datalens.common.web.DomainException;
import static com.datalens.model.ModelModels.*;

@Component
public class ModelOutputValidator {
    private final Validator validator;
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(16000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public ModelOutputValidator(Validator validator){this.validator=validator;}
    public String schema(){return new org.springframework.ai.converter.BeanOutputConverter<>(Structured.class,json).getJsonSchema();}
    public Structured parse(String text,Publication publication){
        try {
            if(text==null||text.length()>64000)throw invalid();
            var output=json.readValue(text,Structured.class);
            if(output==null||!validator.validate(output).isEmpty())throw invalid();
            var intent=output.intent();var plan=output.plan();
            Set<String> metrics=new HashSet<>();for(var m:eligible(publication))metrics.add(m.id());
            Set<String> dimensions=new HashSet<>();publication.document().dimensions().forEach(d->dimensions.add(d.id()));
            if(!metrics.containsAll(intent.metricIds())||!dimensions.containsAll(intent.dimensionIds())||new HashSet<>(intent.metricIds()).size()!=intent.metricIds().size()||new HashSet<>(intent.dimensionIds()).size()!=intent.dimensionIds().size())throw invalid();
            if(intent.needsClarification()) {
                if(intent.clarification().isBlank()||!plan.metricId().isEmpty()||!output.chartSpec().type().equals("TABLE")||output.chartSpec().dimension()!=null||!output.chartSpec().measures().isEmpty())throw invalid();
            } else {
                if(!intent.clarification().isEmpty()||intent.metricIds().size()!=1||!intent.metricIds().getFirst().equals(plan.metricId())||!intent.dimensionIds().isEmpty()||plan.rowLimit()!=1000)throw invalid();
                var metric=eligible(publication).stream().filter(m->m.id().equals(plan.metricId())).findFirst().orElseThrow(ModelOutputValidator::invalid);
                var aliases=aliases(metric);var chart=output.chartSpec();
                if(!aliases.containsAll(chart.measures())||new HashSet<>(chart.measures()).size()!=chart.measures().size())throw invalid();
                if(chart.type().equals("TABLE")){if(chart.dimension()!=null)throw invalid();}
                else if(chart.type().equals("KPI")){if(chart.dimension()!=null||chart.measures().size()!=1)throw invalid();}
                else if(chart.dimension()==null||!aliases.contains(chart.dimension())||chart.measures().isEmpty()||chart.measures().contains(chart.dimension())||(chart.type().equals("PIE")&&chart.measures().size()!=1))throw invalid();
            }
            return output;
        } catch(Exception failure){throw invalid();}
    }
    /** Only PUBLIC semantic fields are eligible for outbound model metadata. */
    public static List<Metric> eligible(Publication p){Set<String> fields=new HashSet<>();p.document().fields().stream().filter(f->f.sensitivity().equals("PUBLIC")).forEach(f->fields.add(f.id()));return p.document().metrics().stream().filter(m->fields.containsAll(m.fieldIds())&&fields.contains(m.timeFieldId())).toList();}
    public static List<String> aliases(Metric metric){
        try {var select=(PlainSelect)CCJSqlParserUtil.parse(metric.sql());var names=new ArrayList<String>();
            for(var item:select.getSelectItems()) {String name=item.getAlias()!=null?item.getAlias().getName():item.getExpression() instanceof Column c?c.getColumnName():null;
                if(name==null||!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")||names.contains(name))throw invalid();names.add(name);}
            return List.copyOf(names);
        }catch(Exception bad){throw invalid();}
    }
    static DomainException invalid(){return new DomainException(422,"MODEL_OUTPUT_INVALID","模型输出未通过结构或业务字段校验");}
}
