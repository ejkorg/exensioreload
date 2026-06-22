package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchLogServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ElasticsearchLogService buildSvc(CpElasticsearchProperties props) {
        return new ElasticsearchLogService(null, props, mapper);
    }

    // ── buildQuery: service.country field mapping ─────────────────────────────

    @Test
    void buildQuery_usesMappedFieldWhenProvided() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("PHO");
        props.getServiceCountryFieldByLocation().put("EXTERNAL-PROD", "service_country");

        String json = buildSvc(props).buildQuery("FILE1", "DATA123", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");
        assertTrue(must.isArray());

        boolean found = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.has("service_country") && term.get("service_country").asText().equals("PHO")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected mapped field 'service_country' with value PHO in must clauses");
    }

    @Test
    void buildQuery_fallsBackToDefaultFieldWhenNoMapping() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("PHO");

        String json = buildSvc(props).buildQuery("FILE1", "DATA123", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");
        assertTrue(must.isArray());

        boolean foundDefault = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.has("service.country") && term.get("service.country").asText().equals("PHO")) {
                foundDefault = true;
                break;
            }
        }
        assertTrue(foundDefault, "Expected default field 'service.country' with value PHO in must clauses");
    }

    @Test
    void buildQuery_noCountryFilter_whenBlank() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");
        props.setServiceCountryFilter("");

        String json = buildSvc(props).buildQuery("FILE1", "DATA123", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "EXTERNAL-PROD");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");
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

    // ── buildQuery: idFile term in must array ─────────────────────────────────

    @Test
    void buildQuery_includesIdFileTermWhenNonBlank() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");

        String json = buildSvc(props).buildQuery("FILE123", "DATA456", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "SITE1");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");

        boolean hasIdFile = false;
        boolean hasIdData = false;
        for (JsonNode clause : must) {
            JsonNode term = clause.path("term");
            if (term.has("idFile") && term.get("idFile").asText().equals("FILE123")) hasIdFile = true;
            if (term.has("idData") && term.get("idData").asText().equals("DATA456")) hasIdData = true;
        }
        assertTrue(hasIdFile, "Expected idFile term clause in must array");
        assertTrue(hasIdData, "Expected idData term clause in must array");
    }

    @Test
    void buildQuery_omitsIdFileTermWhenBlank() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");

        String json = buildSvc(props).buildQuery("", "DATA456", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "SITE1");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");

        boolean hasIdFile = false;
        for (JsonNode clause : must) {
            if (clause.path("term").has("idFile")) {
                hasIdFile = true;
                break;
            }
        }
        assertFalse(hasIdFile, "Expected no idFile term clause when idFile is blank");
    }

    @Test
    void buildQuery_omitsIdFileTermWhenNull() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");

        String json = buildSvc(props).buildQuery(null, "DATA456", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "SITE1");
        JsonNode must = mapper.readTree(json).path("query").path("bool").path("must");

        boolean hasIdFile = false;
        for (JsonNode clause : must) {
            if (clause.path("term").has("idFile")) {
                hasIdFile = true;
                break;
            }
        }
        assertFalse(hasIdFile, "Expected no idFile term clause when idFile is null");
    }

    // ── buildQuery: should clauses and minimum_should_match ───────────────────

    @Test
    void buildQuery_containsShouldClausesAndMinimumShouldMatch() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");

        String json = buildSvc(props).buildQuery("FILE1", "DATA1", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "SITE1");
        JsonNode bool = mapper.readTree(json).path("query").path("bool");

        JsonNode should = bool.path("should");
        assertTrue(should.isArray() && should.size() == 4, "Expected 4 should clauses");
        assertEquals(1, bool.path("minimum_should_match").asInt(), "Expected minimum_should_match=1");
    }

    @Test
    void buildQuery_sourceIncludesIdFileAndIdData() throws Exception {
        CpElasticsearchProperties props = new CpElasticsearchProperties();
        props.setCpConfigFilter("*sender*");

        String json = buildSvc(props).buildQuery("FILE1", "DATA1", "LOT1",
                Instant.parse("2024-01-01T00:00:00Z"), "SITE1");
        JsonNode source = mapper.readTree(json).path("_source");

        assertTrue(source.isArray(), "Expected _source to be an array");
        boolean hasIdFile = false, hasIdData = false;
        for (JsonNode f : source) {
            if ("idFile".equals(f.asText())) hasIdFile = true;
            if ("idData".equals(f.asText())) hasIdData = true;
        }
        assertTrue(hasIdFile, "Expected idFile in _source");
        assertTrue(hasIdData, "Expected idData in _source");
    }
}
