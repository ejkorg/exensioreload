package com.onsemi.cim.apps.exensio.exensioreload.controller;

public interface ProbeStrategy {
    boolean probe(String urlStr, int timeoutMs);
}
