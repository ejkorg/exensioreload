package com.onsemi.cim.apps.exensio.exensioreload.config;

public class CrontabJob {
    private String schedule;
    private String command;
    private String rawLine;

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getRawLine() { return rawLine != null ? rawLine : (schedule != null ? schedule + " " + command : command); }
    public void setRawLine(String rawLine) { this.rawLine = rawLine; }
}
