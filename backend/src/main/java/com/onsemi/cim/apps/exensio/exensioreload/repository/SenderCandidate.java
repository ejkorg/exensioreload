package com.onsemi.cim.apps.exensio.exensioreload.repository;

public class SenderCandidate {
    private final Integer idSender;
    private final String name;
    private final String whereCondition;
    private final Integer port;

    public SenderCandidate(Integer idSender, String name) {
        this(idSender, name, null, null);
    }

    public SenderCandidate(Integer idSender, String name, String whereCondition) {
        this(idSender, name, whereCondition, null);
    }

    public SenderCandidate(Integer idSender, String name, String whereCondition, Integer port) {
        this.idSender = idSender;
        this.name = name;
        this.whereCondition = whereCondition;
        this.port = port;
    }

    public Integer getIdSender() { return idSender; }
    public String getName() { return name; }
    public String getWhereCondition() { return whereCondition; }
    public Integer getPort() { return port; }
}
