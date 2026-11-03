package com.datalens.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Offline tests of the packaged drivers' actual conversion factories, not a cluster acceptance test. */
class QueryDriverValueFactoryTest {
    @Test void starRocksScalarFactoryPreservesPrecisionButStreamFactoryRejectsNumericConversion() throws Exception {
        var properties=new com.starrocks.cj.conf.DefaultPropertySet();
        var strings=new com.starrocks.cj.result.StringValueFactory(properties);
        var streams=new com.starrocks.cj.result.BinaryStreamValueFactory(properties);
        var decimal=new BigDecimal("9007199254740993.1200");
        assertThat(strings.createFromBigDecimal(decimal)).isEqualTo("9007199254740993.1200");
        assertThat(strings.createFromLong(9007199254740993L)).isEqualTo("9007199254740993");
        assertThat(strings.createFromBigInteger(new BigInteger("18446744073709551615"))).isEqualTo("18446744073709551615");
        assertThat(strings.createFromNull()).isNull();
        assertThatThrownBy(()->streams.createFromBigDecimal(decimal)).isInstanceOf(com.starrocks.cj.exceptions.DataConversionException.class);
        assertThatThrownBy(()->streams.createFromLong(9007199254740993L)).isInstanceOf(com.starrocks.cj.exceptions.DataConversionException.class);
        byte[] text="中é😀".getBytes(StandardCharsets.UTF_8);
        try(var stream=streams.createFromBytes(text,0,text.length,null)) {assertThat(stream.readAllBytes()).isEqualTo(text);}
    }
    @Test void mysqlScalarFactoryPreservesPrecisionButStreamFactoryRejectsNumericConversion() throws Exception {
        var properties=new com.mysql.cj.conf.DefaultPropertySet();
        var strings=new com.mysql.cj.result.StringValueFactory(properties);
        var streams=new com.mysql.cj.result.BinaryStreamValueFactory(properties);
        var decimal=new BigDecimal("9007199254740993.1200");
        assertThat(strings.createFromBigDecimal(decimal)).isEqualTo("9007199254740993.1200");
        assertThat(strings.createFromLong(9007199254740993L)).isEqualTo("9007199254740993");
        assertThat(strings.createFromBigInteger(new BigInteger("18446744073709551615"))).isEqualTo("18446744073709551615");
        assertThat(strings.createFromNull()).isNull();
        assertThatThrownBy(()->streams.createFromBigDecimal(decimal)).isInstanceOf(com.mysql.cj.exceptions.DataConversionException.class);
        assertThatThrownBy(()->streams.createFromLong(9007199254740993L)).isInstanceOf(com.mysql.cj.exceptions.DataConversionException.class);
        byte[] text="中é😀".getBytes(StandardCharsets.UTF_8);
        try(var stream=streams.createFromBytes(text,0,text.length,null)) {assertThat(stream.readAllBytes()).isEqualTo(text);}
    }
}
