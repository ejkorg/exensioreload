package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {
    private String from;
    private List<String> resetUrlBase = new ArrayList<>();
    private int resetUrlProbeFirst = 1;
    private int resetUrlProbeTimeoutMs = 1000;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public List<String> getResetUrlBase() { return resetUrlBase; }
    public void setResetUrlBase(List<String> resetUrlBase) { this.resetUrlBase = resetUrlBase; }

    public int getResetUrlProbeFirst() { return resetUrlProbeFirst; }
    public void setResetUrlProbeFirst(int resetUrlProbeFirst) { this.resetUrlProbeFirst = resetUrlProbeFirst; }

    public int getResetUrlProbeTimeoutMs() { return resetUrlProbeTimeoutMs; }
    public void setResetUrlProbeTimeoutMs(int resetUrlProbeTimeoutMs) { this.resetUrlProbeTimeoutMs = resetUrlProbeTimeoutMs; }
}
