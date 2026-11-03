package com.datalens.query;
import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.datalens.common.web.DomainException;
import static com.datalens.query.QueryModels.*;
public final class QueryParameters {
    private QueryParameters() {}
    public static Object value(Parameter p) {
        try {
            if(p==null || p.type()==null || (p.value()!=null && p.value().length()>4000))throw new IllegalArgumentException();
            if("NULL".equals(p.type())) {if(p.value()!=null)throw new IllegalArgumentException();return null;}
            if(p.value()==null)throw new IllegalArgumentException();
            return switch(p.type()) {
                case "STRING" -> p.value();
                case "DECIMAL" -> {var n=new BigDecimal(p.value());if(n.precision()>38||Math.abs(n.scale())>18)throw new IllegalArgumentException();yield n;}
                case "INTEGER" -> Long.valueOf(p.value());
                case "BOOLEAN" -> {if(!Set.of("true","false").contains(p.value()))throw new IllegalArgumentException();yield Boolean.valueOf(p.value());}
                case "DATE" -> java.sql.Date.valueOf(LocalDate.parse(p.value()));
                case "TIMESTAMP" -> Timestamp.valueOf(LocalDateTime.parse(p.value()));
                default -> throw new IllegalArgumentException();
            };
        } catch(RuntimeException failure) {throw new DomainException(400,"QUERY_PARAMETER_INVALID","查询参数格式无效");}
    }
    public static void bind(PreparedStatement statement,List<Parameter> parameters) throws SQLException {
        for(int i=0;i<parameters.size();i++) {
            Object value=value(parameters.get(i));
            if(value==null)statement.setNull(i+1,Types.NULL);else statement.setObject(i+1,value);
        }
    }
}
