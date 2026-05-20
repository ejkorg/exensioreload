package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

/**
 * Result of searching for a file location in environment folders.
 */
public class FileLocationResult {
    private String originalFilename;
    private String searchPattern;
    private String environment;
    private List<String> foundPaths;
    private List<String> foundInFolders; // Which folder type (inbox, dearchive, staging, etc.)
    private String targetFolder; // Primary target folder for this environment (where files end up)
    private boolean found;
    private String errorMessage;
    
    public FileLocationResult() {}
    
    public FileLocationResult(String originalFilename, String searchPattern, String environment) {
        this.originalFilename = originalFilename;
        this.searchPattern = searchPattern;
        this.environment = environment;
        this.found = false;
    }
    
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    
    public String getSearchPattern() { return searchPattern; }
    public void setSearchPattern(String searchPattern) { this.searchPattern = searchPattern; }
    
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    
    public List<String> getFoundPaths() { return foundPaths; }
    public void setFoundPaths(List<String> foundPaths) { 
        this.foundPaths = foundPaths;
        this.found = foundPaths != null && !foundPaths.isEmpty();
    }
    
    public boolean isFound() { return found; }
    public void setFound(boolean found) { this.found = found; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public List<String> getFoundInFolders() { return foundInFolders; }
    public void setFoundInFolders(List<String> foundInFolders) { this.foundInFolders = foundInFolders; }
    
    public String getTargetFolder() { return targetFolder; }
    public void setTargetFolder(String targetFolder) { this.targetFolder = targetFolder; }
}
