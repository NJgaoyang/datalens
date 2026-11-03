package com.datalens.foundation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemInfoServiceTest {
    @Test void readsProductNameFromPersistence() {
        PlatformSettingMapper mapper = mock(PlatformSettingMapper.class);
        when(mapper.productName()).thenReturn("DataLens");
        SystemInfo info = new SystemInfoService(mapper, "0.1.0").getInfo();
        assertThat(info.name()).isEqualTo("DataLens");
        assertThat(info.enabledModules()).containsExactly("foundation", "identity", "datasource", "query", "chart", "semantic");
        verify(mapper).productName();
    }

    @Test void missingMigrationCannotLookLikeSuccess() {
        PlatformSettingMapper mapper = mock(PlatformSettingMapper.class);
        assertThatThrownBy(() -> new SystemInfoService(mapper, "0.1.0").getInfo())
                .isInstanceOf(IllegalStateException.class);
    }
}

