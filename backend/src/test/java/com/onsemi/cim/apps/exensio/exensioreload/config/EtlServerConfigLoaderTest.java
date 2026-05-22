package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtlServerConfigLoaderTest {

    @Test
    void serverMatchesSite_matchesPrefixProdSuffix() {
        assertTrue(EtlServerConfigLoader.serverMatchesSite("CEBU-PROD", "CEBU"));
        assertTrue(EtlServerConfigLoader.serverMatchesSite("cebu-prod", "CEBU"));
        assertFalse(EtlServerConfigLoader.serverMatchesSite("CNK-ONSC-PROD", "CEBU"));
    }

    @Test
    void load_readsClasspathEtlserversYml() {
        EtlServerConfigLoader loader = new EtlServerConfigLoader();
        loader.load();
        assertTrue(loader.hasConfigs(), "etlservers.yml should load at least one server in tests");
        assertFalse(loader.getConfigs().isEmpty());
        EtlServerConfig first = loader.getConfigs().get(0);
        assertFalse(first.getHost().isBlank());
    }

    @Test
    void getConfigsForSite_filtersBySite() {
        EtlServerConfigLoader loader = new EtlServerConfigLoader();
        loader.load();
        var cebu = loader.getConfigsForSite("CEBU");
        assertFalse(cebu.isEmpty());
        assertTrue(cebu.stream().anyMatch(c -> c.getName().contains("CEBU")));
    }
}
