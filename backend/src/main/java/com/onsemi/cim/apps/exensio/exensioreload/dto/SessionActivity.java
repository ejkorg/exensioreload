package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record SessionActivity(
    String timestamp,
    String type,
    String filename,
    String message,
    Object details
) {}
