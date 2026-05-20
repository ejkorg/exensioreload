package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public class SearchPage {
    private List<SearchResult> items;
    private String nextPageToken;

    public SearchPage() {}

    public SearchPage(List<SearchResult> items, String nextPageToken) {
        this.items = items;
        this.nextPageToken = nextPageToken;
    }

    public List<SearchResult> getItems() { return items; }
    public void setItems(List<SearchResult> items) { this.items = items; }

    public String getNextPageToken() { return nextPageToken; }
    public void setNextPageToken(String nextPageToken) { this.nextPageToken = nextPageToken; }
}
