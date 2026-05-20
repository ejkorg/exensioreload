package com.onsemi.cim.apps.exensio.exensioreload.config;

public class CrontabJob {
    private String schedule;
    private String command;

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
}
