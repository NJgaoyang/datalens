package com.datalens.datasource;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import com.datalens.common.web.DomainException;
import static com.datalens.datasource.SourceModels.*;

/** Exact table metadata calls only; no catalog-wide enumeration or row queries. */
@Component
public class MetadataCollector {
    public List<ColumnView> collect(Connection connection,SourceView source) throws SQLException {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        var metadata=connection.getMetaData();var result=new ArrayList<ColumnView>();
        String escape=metadata.getSearchStringEscape();
        if(escape==null || escape.isEmpty()) throw new SQLException("Metadata escaping unavailable");
        for(String table:source.tables()) {
            String pattern=escapePattern(table,escape);
            // Connector/J uses JDBC catalog as StarRocks catalog and schema as database.
            try(ResultSet rows=metadata.getColumns(source.catalog(),escapePattern(source.database(),escape),pattern,"%")) {
                int count=0;
                while(rows.next()) {
                    if(System.nanoTime()>deadline || ++count>1000 || result.size()>=10000)
                        throw new DomainException(422,"METADATA_LIMIT","元数据采集超出限制");
                    if(!source.catalog().equals(rows.getString("TABLE_CAT")) ||
                       !source.database().equals(rows.getString("TABLE_SCHEM")) || !table.equals(rows.getString("TABLE_NAME")))
                        throw new SQLException("Metadata scope mismatch");
                    String column=rows.getString("COLUMN_NAME");String type=rows.getString("TYPE_NAME");
                    if(column==null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || type==null || type.length()>128)
                        throw new SQLException("Unsupported metadata identifier");
                    result.add(new ColumnView(source.id(),table,column,type,rows.getInt("ORDINAL_POSITION"),
                        rows.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls));
                }
                if(count==0) throw new DomainException(422,"METADATA_TABLE_UNAVAILABLE","授权表不存在或不可读取");
            }
        }
        return List.copyOf(result);
    }
    static String escapePattern(String value,String escape) {
        return value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");
    }
}
