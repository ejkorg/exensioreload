package com.onsemi.cim.apps.exensio.resender.config;

import org.springframework.core.env.Environment;

public final class ConfigUtils {

    private ConfigUtils() {}

    public static boolean getBooleanFlag(Environment env, String primaryProp, String fallbackProp, boolean defaultValue) {
        if (env == null) return defaultValue;
        String defaultStr = defaultValue ? "true" : "false";
        String v;
        try {
            if (primaryProp != null) {
                if (fallbackProp != null) {
                    v = env.getProperty(primaryProp, env.getProperty(fallbackProp, defaultStr));
                } else {
                    v = env.getProperty(primaryProp, defaultStr);
                }
            } else if (fallbackProp != null) {
                v = env.getProperty(fallbackProp, defaultStr);
            } else {
                v = defaultStr;
            }
        } catch (Exception ignored) {
            v = defaultStr;
        }
        if (v == null) return defaultValue;
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    public static String getString(Environment env, String primaryProp, String fallbackProp, String defaultValue) {
        if (env == null) return defaultValue;
        String v;
        try {
            if (primaryProp != null) {
                if (fallbackProp != null) {
                    v = env.getProperty(primaryProp, env.getProperty(fallbackProp, defaultValue));
                } else {
                    v = env.getProperty(primaryProp, defaultValue);
                }
            } else if (fallbackProp != null) {
                v = env.getProperty(fallbackProp, defaultValue);
            } else {
                v = defaultValue;
            }
        } catch (Exception ignored) {
            v = defaultValue;
        }
        return v;
    }
}
