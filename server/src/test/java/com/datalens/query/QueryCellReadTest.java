package com.datalens.query;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryCellReadTest {
    private ResultSet rows(Reader... values) throws Exception {
        var rows=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(values.length);
        when(rows.next()).thenReturn(true,false);
        for(int i=0;i<values.length;i++) {
            when(metadata.getColumnLabel(i+1)).thenReturn("value"+i);
            when(metadata.getColumnTypeName(i+1)).thenReturn("VARCHAR");
            when(metadata.getColumnType(i+1)).thenReturn(java.sql.Types.VARCHAR);
            when(rows.getCharacterStream(i+1)).thenReturn(values[i]);
        }
        return rows;
    }
    private static final class LazyReader extends Reader {
        final long length;final char character;long consumed;boolean closed;int maxRequest;
        Runnable onRead=()->{};
        LazyReader(long length,char character){this.length=length;this.character=character;}
        public int read(char[] target,int offset,int size) {
            maxRequest=Math.max(maxRequest,size);
            if(consumed==length)return -1;
            int count=(int)Math.min(size,length-consumed);
            Arrays.fill(target,offset,offset+count,character);consumed+=count;onRead.run();return count;
        }
        public void close(){closed=true;}
    }
    @Test void hugeLazyCellStopsAtBudgetPlusOneAndClosesWithoutStringFallback() throws Exception {
        var reader=new LazyReader(10_000_000_000L,'x');var rows=rows(reader);
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Result size limit");
        assertThat(reader.consumed).isEqualTo(QueryService.MAX_RESULT_BYTES+1L);
        assertThat(reader.maxRequest).isLessThanOrEqualTo(2048);assertThat(reader.closed).isTrue();
        verify(rows,never()).getString(anyInt());
    }
    @Test void exactAggregateBudgetAllowsEmptyAndNullButRejectsAnotherByte() throws Exception {
        var first=new LazyReader(QueryService.MAX_RESULT_BYTES-1,'a');
        var result=QueryService.read(rows(first,new StringReader("b"),new StringReader(""),null),new QueryService.Running());
        assertThat(result.rows().getFirst().get(0)).hasSize(QueryService.MAX_RESULT_BYTES-1);
        assertThat(result.rows().getFirst().subList(1,4)).containsExactly("b","",null);
        assertThat(first.closed).isTrue();
        var extra=new LazyReader(100_000_000,'b');
        assertThatThrownBy(()->QueryService.read(rows(new LazyReader(QueryService.MAX_RESULT_BYTES,'a'),extra),new QueryService.Running()))
            .isInstanceOf(SQLException.class).hasMessage("Result size limit");
        assertThat(extra.consumed).isEqualTo(1);assertThat(extra.closed).isTrue();
    }
    @Test void aggregateBudgetAppliesAcrossRows() throws Exception {
        var first=new LazyReader(QueryService.MAX_RESULT_BYTES,'a');var second=new LazyReader(100_000_000,'b');
        var rows=rows(first);when(rows.next()).thenReturn(true,true,false);when(rows.getCharacterStream(1)).thenReturn(first,second);
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Result size limit");
        assertThat(first.closed).isTrue();assertThat(second.closed).isTrue();assertThat(second.consumed).isEqualTo(1);
    }
    @Test void decimalBigintNullAndEmptyRemainExactStrings() throws Exception {
        var rows=rows(null,null,null,new StringReader(""));var metadata=rows.getMetaData();
        when(metadata.getColumnType(1)).thenReturn(java.sql.Types.DECIMAL);when(metadata.getPrecision(1)).thenReturn(38);when(metadata.getScale(1)).thenReturn(4);
        when(metadata.getColumnType(2)).thenReturn(java.sql.Types.BIGINT);when(metadata.getColumnType(3)).thenReturn(java.sql.Types.BIGINT);
        when(rows.getString(1)).thenReturn("9007199254740993.1200");when(rows.getString(2)).thenReturn("9223372036854775807");
        assertThat(QueryService.read(rows,new QueryService.Running()).rows().getFirst())
            .containsExactly("9007199254740993.1200","9223372036854775807",null,"");
        verify(rows,never()).getCharacterStream(1);verify(rows,never()).getCharacterStream(2);verify(rows,never()).getString(4);
    }
    @Test void unknownOrExcessiveDecimalPrecisionAndScaleFailBeforeMaterialization() throws Exception {
        for(int[] shape:new int[][]{{0,0},{1001,0},{38,1001},{38,-1001}}) {
            var rows=rows((Reader)null);var metadata=rows.getMetaData();when(metadata.getColumnType(1)).thenReturn(java.sql.Types.DECIMAL);
            when(metadata.getPrecision(1)).thenReturn(shape[0]);when(metadata.getScale(1)).thenReturn(shape[1]);
            assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Unsupported decimal precision or scale");
            verify(rows,never()).getString(anyInt());verify(rows,never()).getCharacterStream(anyInt());
        }
    }
    @Test void scalarConsumesSameTotalBudgetAndChecksCancellation() throws Exception {
        var rows=rows(new LazyReader(QueryService.MAX_RESULT_BYTES,'a'),null);when(rows.getMetaData().getColumnType(2)).thenReturn(java.sql.Types.BIGINT);when(rows.getString(2)).thenReturn("1");
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Result size limit");
        var control=new QueryService.Running();var cancelled=rows((Reader)null);when(cancelled.getMetaData().getColumnType(1)).thenReturn(java.sql.Types.BIGINT);
        when(cancelled.getString(1)).thenAnswer(inv->{control.request("CANCELLED");return "1";});
        assertThatThrownBy(()->QueryService.read(cancelled,control)).isInstanceOf(SQLException.class).hasMessage("Cancelled");
    }
    @Test void utf8BudgetCountsMultibyteAndSplitSurrogatesLikeJavaEncoding() throws Exception {
        for(String value:new String[]{"é","中","\uD83D\uDE00","\uD800","\uDC00","\uD800x","\uD800\uD800\uDC00"}) {
            int bytes=value.getBytes(StandardCharsets.UTF_8).length;
            Reader split=new StringReader(value) {
                public int read(char[] target,int offset,int count)throws IOException{return super.read(target,offset,Math.min(count,1));}
            };
            var result=QueryService.read(rows(new LazyReader(QueryService.MAX_RESULT_BYTES-bytes,'a'),split),new QueryService.Running());
            assertThat(result.rows().getFirst().get(1)).isEqualTo(value);
            assertThatThrownBy(()->QueryService.read(rows(new LazyReader(QueryService.MAX_RESULT_BYTES-bytes+1,'a'),new StringReader(value)),new QueryService.Running()))
                .isInstanceOf(SQLException.class).hasMessage("Result size limit");
        }
    }
    @Test void supplementaryCharacterAcrossNormalChunkBoundaryIsPreserved() throws Exception {
        String value="x".repeat(2047)+"\uD83D\uDE00"+"中";
        assertThat(QueryService.read(rows(new StringReader(value)),new QueryService.Running()).rows().getFirst()).containsExactly(value);
    }
    @Test void multibyteLazyReaderStopsBeforeReadingWholeValue() throws Exception {
        var reader=new LazyReader(10_000_000_000L,'中');
        assertThatThrownBy(()->QueryService.read(rows(reader),new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Result size limit");
        assertThat(reader.consumed).isLessThanOrEqualTo(QueryService.MAX_RESULT_BYTES/3L+2048);assertThat(reader.closed).isTrue();
    }
    @Test void cancellationDuringCellReadStopsAndClosesImmediately() throws Exception {
        var control=new QueryService.Running();var reader=new LazyReader(10_000_000_000L,'x');reader.onRead=()->control.request("CANCELLED");
        assertThatThrownBy(()->QueryService.read(rows(reader),control)).isInstanceOf(SQLException.class).hasMessage("Cancelled");
        assertThat(reader.consumed).isEqualTo(2048);assertThat(reader.closed).isTrue();
    }
    @Test void cancellationBeforeReadingDoesNotAdvanceResultSetOrOpenCell() throws Exception {
        var control=new QueryService.Running();control.request("CANCELLED");var rows=rows(new StringReader("value"));
        assertThatThrownBy(()->QueryService.read(rows,control)).isInstanceOf(SQLException.class).hasMessage("Cancelled");
        verify(rows,never()).next();verify(rows,never()).getCharacterStream(anyInt());
    }
    @Test void cancellationWhileOpeningStreamStillClosesIt() throws Exception {
        var control=new QueryService.Running();var reader=new LazyReader(100,'x');var rows=rows(reader);
        when(rows.getCharacterStream(1)).thenAnswer(inv->{control.request("CANCELLED");return reader;});
        assertThatThrownBy(()->QueryService.read(rows,control)).isInstanceOf(SQLException.class).hasMessage("Cancelled");
        assertThat(reader.consumed).isZero();assertThat(reader.closed).isTrue();
    }
    @Test void interruptedThreadDoesNotConsumeAndKeepsInterruptFlag() throws Exception {
        var rows=rows(new StringReader("value"));Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();verify(rows,never()).next();
        } finally {Thread.interrupted();}
    }
    @Test void unsupportedStreamsFailClosedWithoutGetStringFallback() throws Exception {
        var rows=rows(new StringReader("unused"));
        when(rows.getCharacterStream(1)).thenThrow(new SQLFeatureNotSupportedException("unsupported"));
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLFeatureNotSupportedException.class);
        when(rows.next()).thenReturn(true,false);
        doThrow(new AbstractMethodError()).when(rows).getCharacterStream(1);
        assertThatThrownBy(()->QueryService.read(rows,new QueryService.Running())).isInstanceOf(SQLFeatureNotSupportedException.class);
        verify(rows,never()).getString(anyInt());
    }
    @Test void ioFailureClosesReaderAndDoesNotReturnPartialCell() throws Exception {
        var reader=mock(Reader.class);when(reader.read(any(char[].class),eq(0),anyInt())).thenThrow(new IOException("read failed"));
        assertThatThrownBy(()->QueryService.read(rows(reader),new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Cannot read result character stream");
        verify(reader).close();
    }
    @Test void nonProgressingReaderFailsInsteadOfSpinning() throws Exception {
        var reader=mock(Reader.class);
        assertThatThrownBy(()->QueryService.read(rows(reader),new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Character stream made no progress");
        verify(reader,times(1)).read(any(char[].class),eq(0),anyInt());verify(reader).close();
    }
    @Test void closeFailurePreventsSuccessfulResult() throws Exception {
        var reader=mock(Reader.class);when(reader.read(any(char[].class),eq(0),anyInt())).thenReturn(-1);doThrow(new IOException("close failed")).when(reader).close();
        assertThatThrownBy(()->QueryService.read(rows(reader),new QueryService.Running())).isInstanceOf(SQLException.class).hasMessage("Cannot read result character stream");
    }
}
