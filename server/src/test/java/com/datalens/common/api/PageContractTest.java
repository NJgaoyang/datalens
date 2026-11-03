package com.datalens.common.api;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PageContractTest {
    @Test void usesExplicitDefaults() {
        assertThat(new PageQuery(null, null)).isEqualTo(new PageQuery(1, 20));
    }

    @Test void rejectsInvalidPageAndUnboundedSize() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new PageQuery(0, 201))).hasSize(2);
            assertThat(factory.getValidator().validate(new PageQuery(1, 200))).isEmpty();
        }
    }

    @Test void pageResultIsImmutableAndChecksBounds() {
        PageResult<String> result = new PageResult<>(List.of("item"), 1, 1, 20);
        assertThatThrownBy(() -> result.items().add("bad")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PageResult<>(List.of(), -1, 1, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
