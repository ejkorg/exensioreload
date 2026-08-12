package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "refdb")
public class RefDbProperties {
    private String dbType = "oracle";
    private String host;
    private int port;
    private String database;
    private String sid;
    private String service;
    private String user;
    private String password;
    private String stagingTable = "SENDER_STAGE";
    private String bootstrapAdmins;
    private Pool pool = new Pool();
    private Dispatch dispatch = new Dispatch();

    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getSid() { return sid; }
    public void setSid(String sid) { this.sid = sid; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getStagingTable() { return stagingTable; }
    public void setStagingTable(String stagingTable) { this.stagingTable = stagingTable; }
    public String getBootstrapAdmins() { return bootstrapAdmins; }
    public void setBootstrapAdmins(String bootstrapAdmins) { this.bootstrapAdmins = bootstrapAdmins; }
    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }
    public Dispatch getDispatch() { return dispatch; }
    public void setDispatch(Dispatch dispatch) { this.dispatch = dispatch; }

    public String buildJdbcUrl() {
        if (isPostgres()) {
            String dbName = database;
            if (dbName == null || dbName.isBlank()) {
                dbName = service;
            }
            if (dbName == null || dbName.isBlank()) {
                dbName = sid;
            }
            return String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
        }
        if (service != null && !service.isBlank()) {
            return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, service);
        }
        if (sid != null && !sid.isBlank()) {
            return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, sid);
        }
        return String.format("jdbc:oracle:thin:@%s:%d", host, port);
    }

    public boolean isPostgres() {
        return dbType != null && dbType.trim().equalsIgnoreCase("postgresql");
    }

    public static class Pool {
        private int maxSize = 5;
        private int minIdle = 1;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
    }

    public static class Dispatch {
        private int perSend = 100;
        private long intervalMs = 60000L;
        private int maxQueueSize = 1000;
        private long monitorIntervalMs = 120000L;

        public int getPerSend() { return perSend; }
        public void setPerSend(int perSend) { this.perSend = perSend; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }
        public long getMonitorIntervalMs() { return monitorIntervalMs; }
        public void setMonitorIntervalMs(long monitorIntervalMs) { this.monitorIntervalMs = monitorIntervalMs; }
    }
}
