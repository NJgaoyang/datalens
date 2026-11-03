package com.datalens.foundation;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {
    private final PlatformSettingMapper mapper;
    private final String version;

    public SystemInfoService(PlatformSettingMapper mapper, @Value("${datalens.version}") String version) {
        this.mapper = mapper;
        this.version = version;
    }

    public SystemInfo getInfo() {
        String name = mapper.productName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Foundation migration is incomplete");
        }
        return new SystemInfo(name, version, "M00", List.of("foundation"));
    }
}
