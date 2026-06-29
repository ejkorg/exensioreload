package com.onsemi.cim.apps.exensio.exensioreload.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Glob-style device patterns using {@code *} as a wildcard (maps to SQL {@code LIKE} {@code %}).
 */
public final class DevicePatternUtils {

    private DevicePatternUtils() {
    }

    public static boolean containsWildcard(String value) {
        return value != null && value.indexOf('*') >= 0;
    }

    /**
     * Converts an upper-case glob pattern to a SQL LIKE pattern. Literal {@code %}, {@code _},
     * and {@code \} are escaped for use with {@code ESCAPE '\'}.
     */
    public static String toSqlLikePattern(String upperGlob) {
        if (upperGlob == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < upperGlob.length(); i++) {
            char c = upperGlob.charAt(i);
            if (c == '*') {
                sb.append('%');
            } else if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean matches(String device, String pattern) {
        if (device == null || pattern == null) {
            return false;
        }
        String normalizedDevice = device.trim().toUpperCase(Locale.ROOT);
        String normalizedPattern = pattern.trim().toUpperCase(Locale.ROOT);
        if (!containsWildcard(normalizedPattern)) {
            return normalizedDevice.equals(normalizedPattern);
        }
        return globToRegex(normalizedPattern).matcher(normalizedDevice).matches();
    }

    private static Pattern globToRegex(String upperGlob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < upperGlob.length(); i++) {
            char c = upperGlob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+^$|?".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
