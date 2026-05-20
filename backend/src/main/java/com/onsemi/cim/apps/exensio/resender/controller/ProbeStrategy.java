package com.onsemi.cim.apps.exensio.resender.controller;

public interface ProbeStrategy {
    boolean probe(String urlStr, int timeoutMs);
}
