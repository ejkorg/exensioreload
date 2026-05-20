package com.onsemi.cim.apps.exensio.resender.dto;

public record SessionActivity(
    String timestamp,
    String type,
    String filename,
    String message,
    Object details
) {}
