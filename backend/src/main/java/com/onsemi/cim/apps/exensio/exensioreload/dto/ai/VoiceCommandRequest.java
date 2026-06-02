package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

/**
 * Request for voice command processing.
 */
public class VoiceCommandRequest {
    private String command;
    private String language = "en-US";

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}