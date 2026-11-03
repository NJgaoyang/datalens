package com.datalens.foundation;

import java.util.List;

public record SystemInfo(String name, String version, String stage, List<String> enabledModules) {
    public SystemInfo {
        enabledModules = List.copyOf(enabledModules);
    }
}
