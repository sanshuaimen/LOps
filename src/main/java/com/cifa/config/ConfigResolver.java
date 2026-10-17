package com.cifa.config;

import java.util.Map;

public final class ConfigResolver {

    private ConfigResolver() {
    }

    public static String resolve(String cliValue, String envValue, Map<String, String> fileConfig,
                                  String configKey, String builtInDefault) {
        if (notBlank(cliValue)) {
            return cliValue;
        }
        if (notBlank(envValue)) {
            return envValue;
        }
        String fromFile = fileConfig.get(configKey);
        if (notBlank(fromFile)) {
            return fromFile;
        }
        return builtInDefault;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
