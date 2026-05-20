package com.onsemi.cim.apps.exensio.resender.controller;

import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;

@Component
public class DefaultHttpProbeStrategy implements ProbeStrategy {
    @Override
    public boolean probe(String urlStr, int timeoutMs) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code >= 0 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }
}
