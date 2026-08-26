package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection properties for the pp_log Oracle database.
 *
 * <p>This is a separate connection from the main {@code refdb.*} staging connection,
 * allowing pp_log queries to target a different environment (e.g. PRODUCTION) while
 * the staging connection remains pointed at QA.</p>
 *
 * <p>If {@code refdb.pplog.host} is blank or not configured, the pp_log queries
 * fall back to the main {@code refdb.*} datasource.</p>
 *
 * <p>Example configuration in {@code application-onsemi-oracle.yml}:</p>
 * <pre>
 * refdb:
 *   pplog:
 *     host: exnprd-db.onsemi.com
 *     port: 1739
 *     service: EXNPRD.onsemi.com
 *     user: refdb
 *     password: "secret"
 *     pool:
 *       max-size: 5
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "refdb.pplog")
public class PpLogDbProperties {

    private String host;
    private int port;
    private String sid;
    private String service;
    private String user;
    private String password;
    private Pool pool = new Pool();
    /**
     * Whether pp_log queries are enabled for this deployment.
     * Set to {@code false} at sites where the {@code pp_log} table does not exist.
     * Defaults to {@code true} so existing deployments are unaffected.
     */
    private boolean enabled = true;

    /**
     * Timezone of the Oracle server hosting pp_log.
     * pp_log.process_datetime is written by third-party ETL processes in the
     * server's local timezone (no TZ info stored). This timezone is used to
     * convert the UTC enrichmentStartedAt before querying, ensuring correct
     * comparison against process_datetime.
     *
     * Example values: "America/Phoenix", "Europe/Brussels", "Asia/Tokyo", "UTC"
     * Defaults to "UTC" for backward compatibility.
     */
    private String serverTimezone = "UTC";

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getSid() { return sid; }
    public void setSid(String sid) { this.sid = sid; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServerTimezone() { return serverTimezone; }
    public void setServerTimezone(String serverTimezone) { this.serverTimezone = serverTimezone == null ? "UTC" : serverTimezone; }

    /** Returns true if a separate pp_log host is configured. */
    public boolean isConfigured() {
        return host != null && !host.isBlank();
    }

    /**
     * Returns true if pp_log queries should be attempted.
     * Requires both {@code enabled=true} AND a non-blank {@code host}.
     * A site without a pp_log host configured is treated as "not available"
     * regardless of the enabled flag.
     */
    public boolean isPpLogAvailable() {
        return enabled && isConfigured();
    }

    public String buildJdbcUrl() {
        if (service != null && !service.isBlank()) {
            return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, service);
        }
        if (sid != null && !sid.isBlank()) {
            return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, sid);
        }
        return String.format("jdbc:oracle:thin:@%s:%d", host, port);
    }

    public static class Pool {
        private int maxSize = 3;
        private int minIdle = 1;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
    }
}
