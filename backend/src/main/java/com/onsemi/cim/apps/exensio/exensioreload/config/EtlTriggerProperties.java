package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Master switch for the optional ETL SSH trigger (SSH + remote crontab command).
 * Queue-based CP dispatch via {@code SenderDispatchService} is independent of this flag.
 */
@Component
@ConfigurationProperties(prefix = "etl.trigger")
public class EtlTriggerProperties {

    /**
     * Master switch for the ETL SSH trigger. Defaults to true.
     * Can be toggled via ETL_TRIGGER_ENABLED=false.
     */
    private boolean enabled = true;
    private boolean dryRun = false;
    private int rerunPoolSize = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getRerunPoolSize() {
        return rerunPoolSize;
    }

    public void setRerunPoolSize(int rerunPoolSize) {
        this.rerunPoolSize = rerunPoolSize;
    }

    private int maxRerunIterations = 10;

    public int getMaxRerunIterations() {
        return maxRerunIterations;
    }

    public void setMaxRerunIterations(int maxRerunIterations) {
        this.maxRerunIterations = maxRerunIterations;
    }
}
