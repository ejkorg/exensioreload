package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PipelineConfigCache.
 * 
 * Tests verify cache behavior including loading, caching, invalidation, and configuration exceptions.
 * Uses mocked PipelineConfigLoader to control cache behavior.
 */
@DisplayName("PipelineConfigCache")
class PipelineConfigCacheTest {

    private PipelineConfigLoader mockLoader;
    private PipelineConfigCache cache;

    @BeforeEach
    void setup() {
        mockLoader = mock(PipelineConfigLoader.class);
        cache = new PipelineConfigCache(mockLoader, 5);
    }

    @Test
    @DisplayName("should load config on cache miss")
    void testLoadOnCacheMiss() throws PipelineConfigException {
        String site = "CEBU-PROD";
        PipelineConfig config = createTestConfig(site);
        when(mockLoader.loadPipelineConfig(site.toUpperCase())).thenReturn(Optional.of(config));

        Optional<PipelineConfig> result = cache.getConfig(site);

        assertTrue(result.isPresent());
        assertEquals(config.site(), result.get().site());
        verify(mockLoader, times(1)).loadPipelineConfig(site.toUpperCase());
    }

    @Test
    @DisplayName("should cache config and not reload on second access")
    void testCachingPreventsDuplicateLoads() throws PipelineConfigException {
        String site = "CEBU-PROD";
        PipelineConfig config = createTestConfig(site);
        when(mockLoader.loadPipelineConfig(site.toUpperCase())).thenReturn(Optional.of(config));

        // First call
        Optional<PipelineConfig> result1 = cache.getConfig(site);
        // Second call (should use cache)
        Optional<PipelineConfig> result2 = cache.getConfig(site);

        assertTrue(result1.isPresent());
        assertTrue(result2.isPresent());
        assertEquals(result1.get().site(), result2.get().site());
        // Loader should only be called once due to caching
        verify(mockLoader, times(1)).loadPipelineConfig(site.toUpperCase());
    }

    @Test
    @DisplayName("should cache empty optional when no pipeline config exists")
    void testCachesEmptyOptional() throws PipelineConfigException {
        String site = "JND-AIZU-PROD";
        when(mockLoader.loadPipelineConfig(site.toUpperCase())).thenReturn(Optional.empty());

        // First call
        Optional<PipelineConfig> result1 = cache.getConfig(site);
        // Second call (should use cache)
        Optional<PipelineConfig> result2 = cache.getConfig(site);

        assertFalse(result1.isPresent());
        assertFalse(result2.isPresent());
        // Loader should only be called once even for empty optional
        verify(mockLoader, times(1)).loadPipelineConfig(site.toUpperCase());
    }

    @Test
    @DisplayName("should invalidate entire cache")
    void testInvalidateAll() throws PipelineConfigException {
        String site1 = "CEBU-PROD";
        String site2 = "JND-AIZU-PROD";
        PipelineConfig config1 = createTestConfig(site1);
        when(mockLoader.loadPipelineConfig(site1.toUpperCase())).thenReturn(Optional.of(config1));
        when(mockLoader.loadPipelineConfig(site2.toUpperCase())).thenReturn(Optional.empty());

        // Load both into cache
        cache.getConfig(site1);
        cache.getConfig(site2);

        verify(mockLoader, times(1)).loadPipelineConfig(site1.toUpperCase());
        verify(mockLoader, times(1)).loadPipelineConfig(site2.toUpperCase());

        // Invalidate all
        cache.invalidateAll();

        // Access again - should reload
        cache.getConfig(site1);
        cache.getConfig(site2);

        verify(mockLoader, times(2)).loadPipelineConfig(site1.toUpperCase());
        verify(mockLoader, times(2)).loadPipelineConfig(site2.toUpperCase());
    }

    @Test
    @DisplayName("should invalidate specific site")
    void testInvalidateSpecificSite() throws PipelineConfigException {
        String site1 = "CEBU-PROD";
        String site2 = "JND-AIZU-PROD";
        PipelineConfig config1 = createTestConfig(site1);
        PipelineConfig config2 = createTestConfig(site2);
        when(mockLoader.loadPipelineConfig(site1.toUpperCase())).thenReturn(Optional.of(config1));
        when(mockLoader.loadPipelineConfig(site2.toUpperCase())).thenReturn(Optional.of(config2));

        // Load both into cache
        cache.getConfig(site1);
        cache.getConfig(site2);

        verify(mockLoader, times(1)).loadPipelineConfig(site1.toUpperCase());
        verify(mockLoader, times(1)).loadPipelineConfig(site2.toUpperCase());

        // Invalidate only site1
        cache.invalidate(site1);

        // Access both again
        cache.getConfig(site1);
        cache.getConfig(site2);

        // site1 should be reloaded, site2 should use cache
        verify(mockLoader, times(2)).loadPipelineConfig(site1.toUpperCase());
        verify(mockLoader, times(1)).loadPipelineConfig(site2.toUpperCase());
    }

    @Test
    @DisplayName("should normalize site name to uppercase")
    void testSiteNameNormalization() throws PipelineConfigException {
        String site = "cebu-prod";
        PipelineConfig config = createTestConfig("CEBU-PROD");
        when(mockLoader.loadPipelineConfig("CEBU-PROD")).thenReturn(Optional.of(config));

        Optional<PipelineConfig> result = cache.getConfig(site);

        assertTrue(result.isPresent());
        verify(mockLoader, times(1)).loadPipelineConfig("CEBU-PROD");
    }

    @Test
    @DisplayName("should handle null site gracefully")
    void testNullSiteHandling() throws PipelineConfigException {
        Optional<PipelineConfig> result = cache.getConfig(null);

        assertFalse(result.isPresent());
        verify(mockLoader, never()).loadPipelineConfig(any());
    }

    @Test
    @DisplayName("should handle blank site gracefully")
    void testBlankSiteHandling() throws PipelineConfigException {
        Optional<PipelineConfig> result = cache.getConfig("   ");

        assertFalse(result.isPresent());
        verify(mockLoader, never()).loadPipelineConfig(any());
    }

    @Test
    @DisplayName("should propagate configuration exceptions")
    void testExceptionPropagation() throws PipelineConfigException {
        String site = "CEBU-PROD";
        PipelineConfigException exception = new PipelineConfigException("Invalid config");
        when(mockLoader.loadPipelineConfig(site.toUpperCase())).thenThrow(exception);

        assertThrows(PipelineConfigException.class, () -> cache.getConfig(site));
    }

    @Test
    @DisplayName("should return correct expiration time")
    void testGetExpireAfterMinutes() {
        assertEquals(5, cache.getExpireAfterMinutes());
    }

    @Test
    @DisplayName("should enforce minimum expiration of 1 minute")
    void testMinimumExpirationEnforced() {
        PipelineConfigCache cacheWithZero = new PipelineConfigCache(mockLoader, 0);
        assertEquals(1, cacheWithZero.getExpireAfterMinutes());

        PipelineConfigCache cacheWithNegative = new PipelineConfigCache(mockLoader, -5);
        assertEquals(1, cacheWithNegative.getExpireAfterMinutes());
    }

    // Helper method to create test configuration
    private PipelineConfig createTestConfig(String site) {
        java.util.List<StageDefinition> stages = java.util.List.of(
            new StageDefinition("cp", StageType.CP, java.util.List.of(), java.util.Map.of())
        );
        java.util.Map<String, StageDefinition> stagesByName = new java.util.HashMap<>();
        stagesByName.put("cp", stages.get(0));
        return new PipelineConfig(site.toUpperCase(), stages, stagesByName);
    }
}
