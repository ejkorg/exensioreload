package com.onsemi.cim.apps.exensio.exensioreload.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevicePatternUtilsTest {

    @Test
    void matches_exactValue() {
        assertThat(DevicePatternUtils.matches("FNB7-8", "FNB7-8")).isTrue();
        assertThat(DevicePatternUtils.matches("FNB7-8", "FNB7-9")).isFalse();
    }

    @Test
    void matches_prefixWildcard() {
        assertThat(DevicePatternUtils.matches("FNB7-8", "FNB7*")).isTrue();
        assertThat(DevicePatternUtils.matches("FNB7-S", "FNB7*")).isTrue();
        assertThat(DevicePatternUtils.matches("FNB8-1", "FNB7*")).isFalse();
    }

    @Test
    void matches_caseInsensitiveInput() {
        assertThat(DevicePatternUtils.matches("fnb7-8", "FNB7*")).isTrue();
    }

    @Test
    void toSqlLikePattern_convertsGlobAndEscapesLiterals() {
        assertThat(DevicePatternUtils.toSqlLikePattern("FNB7*")).isEqualTo("FNB7%");
        assertThat(DevicePatternUtils.toSqlLikePattern("*FNB7*")).isEqualTo("%FNB7%");
        assertThat(DevicePatternUtils.toSqlLikePattern("A_B%C*")).isEqualTo("A\\_B\\%C%");
    }
}
