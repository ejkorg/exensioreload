package com.onsemi.cim.apps.exensio.exensioreload.repository;

public class SenderCandidate {
    private final Integer idSender;
    private final String name;
    private final String whereCondition;

    public SenderCandidate(Integer idSender, String name) {
        this(idSender, name, null);
    }

    public SenderCandidate(Integer idSender, String name, String whereCondition) {
        this.idSender = idSender;
        this.name = name;
        this.whereCondition = whereCondition;
    }

    public Integer getIdSender() { return idSender; }
    public String getName() { return name; }
    public String getWhereCondition() { return whereCondition; }
}
