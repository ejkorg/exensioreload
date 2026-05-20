package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public class SearchCriteria {
    private String environment;
    private List<Integer> years;
    private List<String> lotIds;
    private List<Integer> months;
    private Integer maxResults;
    private String pageToken;

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public List<Integer> getYears() { return years; }
    public void setYears(List<Integer> years) { this.years = years; }

    public List<String> getLotIds() { return lotIds; }
    public void setLotIds(List<String> lotIds) { this.lotIds = lotIds; }

    public List<Integer> getMonths() { return months; }
    public void setMonths(List<Integer> months) { this.months = months; }

    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }

    public String getPageToken() { return pageToken; }
    public void setPageToken(String pageToken) { this.pageToken = pageToken; }
}
