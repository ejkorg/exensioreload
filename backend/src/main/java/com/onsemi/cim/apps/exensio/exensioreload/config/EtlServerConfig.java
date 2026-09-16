package com.onsemi.cim.apps.exensio.exensioreload.config;

public class EtlServerConfig {
    private String name;
    private String host;
    private Integer sshPort = 22;
    private Integer port = 22;
    private Integer socketPort = 60170;
    private String user;
    private String password;
    private Integer timeoutMs = 30000;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getSshPort() { return sshPort != null ? sshPort : (port != null && port != 60170 ? port : 22); }
    public void setSshPort(Integer sshPort) { this.sshPort = sshPort; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public Integer getSocketPort() { return socketPort != null ? socketPort : port; }
    public void setSocketPort(Integer socketPort) { this.socketPort = socketPort; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
}
