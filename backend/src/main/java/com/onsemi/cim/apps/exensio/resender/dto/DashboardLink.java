package com.onsemi.cim.apps.exensio.resender.dto;

public record DashboardLink(String rel, String href, String type) {
    public static DashboardLink of(String rel, String href, String type) {
        return new DashboardLink(rel, href, type);
    }
}
