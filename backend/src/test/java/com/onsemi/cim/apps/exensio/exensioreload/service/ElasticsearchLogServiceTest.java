package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchLogServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildQuery_usesMappedFieldWhenProvided() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("PHO");
        props.getServiceCountryFieldByLocation().put("EXTERNAL-PROD", "service_country");

        ElasticsearchLogService svc = new ElasticsearchLogService(null, props, mapper);

        String json = svc.buildQuery("DATA123", "LOT1", Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode root = mapper.readTree(json);

        JsonNode must = root.path("query").path("bool").path("must");
        assertTrue(must.isArray());

        boolean found = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.has("service_country") && term.get("service_country").asText().equals("PHO")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected mapped field 'service_country' with value PHO to appear in must clauses");
    }

    @Test
    void buildQuery_fallsBackToDefaultFieldWhenNoMapping() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("PHO");
        // no mapping set

        ElasticsearchLogService svc = new ElasticsearchLogService(null, props, mapper);

        String json = svc.buildQuery("DATA123", "LOT1", Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode root = mapper.readTree(json);

        JsonNode must = root.path("query").path("bool").path("must");
        assertTrue(must.isArray());

        boolean foundDefault = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.has("service.country") && term.get("service.country").asText().equals("PHO")) {
                foundDefault = true;
                break;
            }
        }
        assertTrue(foundDefault, "Expected default field 'service.country' with value PHO to appear in must clauses");
    }

    @Test
    void buildQuery_noCountryFilter_whenBlank() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("");

        ElasticsearchLogService svc = new ElasticsearchLogService(null, props, mapper);

        String json = svc.buildQuery("DATA123", "LOT1", Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode root = mapper.readTree(json);

        JsonNode must = root.path("query").path("bool").path("must");
        assertTrue(must.isArray());

        boolean hasCountry = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.fieldNames().hasNext()) {
                String field = term.fieldNames().next();
                if (field.equals("service.country") || field.equals("service_country")) {
                    hasCountry = true;
                    break;
                }
            }
        }
        assertFalse(hasCountry, "Did not expect any service country term when filter is blank");
    }
}
