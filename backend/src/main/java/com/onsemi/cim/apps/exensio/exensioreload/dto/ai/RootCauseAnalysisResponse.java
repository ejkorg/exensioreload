package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for root cause analysis.
 */
public class RootCauseAnalysisResponse {
    private String primaryCause;
    private String confidence;
    private String explanation;
    private List<String> contributingFactors;
    private List<String> similarPastIncidents;
    private List<String> recommendedActions;
    private List<String> affectedComponents;
    private String estimatedTimeToResolve;
    private List<String> documentationLinks;

    public RootCauseAnalysisResponse() {}

    public String getPrimaryCause() { return primaryCause; }
    public void setPrimaryCause(String primaryCause) { this.primaryCause = primaryCause; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public List<String> getContributingFactors() { return contributingFactors; }
    public void setContributingFactors(List<String> contributingFactors) { this.contributingFactors = contributingFactors; }

    public List<String> getSimilarPastIncidents() { return similarPastIncidents; }
    public void setSimilarPastIncidents(List<String> similarPastIncidents) { this.similarPastIncidents = similarPastIncidents; }

    public List<String> getRecommendedActions() { return recommendedActions; }
    public void setRecommendedActions(List<String> recommendedActions) { this.recommendedActions = recommendedActions; }

    public List<String> getAffectedComponents() { return affectedComponents; }
    public void setAffectedComponents(List<String> affectedComponents) { this.affectedComponents = affectedComponents; }

    public String getEstimatedTimeToResolve() { return estimatedTimeToResolve; }
    public void setEstimatedTimeToResolve(String estimatedTimeToResolve) { this.estimatedTimeToResolve = estimatedTimeToResolve; }

    public List<String> getDocumentationLinks() { return documentationLinks; }
    public void setDocumentationLinks(List<String> documentationLinks) { this.documentationLinks = documentationLinks; }
}