package com.datalens.chart;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ChartCapacityTest {
    @Test void defaultsAreFiniteAndPersonalLimitsCannotExceedGlobal() {
        assertThat(ChartCapacity.defaults().assets()).isEqualTo(5000);
        assertThatThrownBy(()->new ChartCapacity(0,1,1,1,1,1,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ChartCapacity(1,2,1,1,1,1,1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ChartCapacity(1000001,1,1,1,1,1,1,1)).isInstanceOf(IllegalArgumentException.class);
    }
}
