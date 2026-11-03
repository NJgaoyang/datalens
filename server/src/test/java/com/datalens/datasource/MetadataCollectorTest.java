package com.datalens.datasource;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.datalens.datasource.SourceModels.*;
class MetadataCollectorTest {
    SourceView source=new SourceView("id","name","10.20.30.40",9030,"default_catalog","sales_db","reader",true,List.of("order_items"),1,"NEVER",null,null);
    @Test void onlyConfiguredScopeIsRequestedAndResourcesClose() throws Exception {
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var rows=mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getColumns("default_catalog","sales\\_db","order\\_items","%")).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);
        when(rows.getString("TABLE_CAT")).thenReturn("default_catalog");when(rows.getString("TABLE_SCHEM")).thenReturn("sales_db");
        when(rows.getString("TABLE_NAME")).thenReturn("order_items");when(rows.getString("COLUMN_NAME")).thenReturn("amount");
        when(rows.getString("TYPE_NAME")).thenReturn("DECIMAL");when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        assertThat(new MetadataCollector().collect(connection,source)).extracting(ColumnView::columnName).containsExactly("amount");
        verify(rows).close();verify(metadata,never()).getCatalogs();verify(metadata,never()).getSchemas();
    }
    @Test void outOfScopeDriverResultFailsClosed() throws Exception {
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var rows=mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getColumns(anyString(),anyString(),anyString(),anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);when(rows.getString("TABLE_CAT")).thenReturn("other_catalog");
        assertThatThrownBy(()->new MetadataCollector().collect(connection,source)).isInstanceOf(SQLException.class);verify(rows).close();
    }
}
