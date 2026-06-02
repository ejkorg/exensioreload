package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for shift handoff summary generation.
 */
public class ShiftHandoffRequest {
    private String shift;           // "MORNING", "AFTERNOON", "NIGHT"
    private String shiftDate;       // Date of the shift
    private String outgoingOperator;
    private String incomingOperator;
    private String site;
    private List<String> filters;   // Optional filters

    public String getShift() { return shift; }
    public void setShift(String shift) { this.shift = shift; }
    public String getShiftDate() { return shiftDate; }
    public void setShiftDate(String shiftDate) { this.shiftDate = shiftDate; }
    public String getOutgoingOperator() { return outgoingOperator; }
    public void setOutgoingOperator(String outgoingOperator) { this.outgoingOperator = outgoingOperator; }
    public String getIncomingOperator() { return incomingOperator; }
    public void setIncomingOperator(String incomingOperator) { this.incomingOperator = incomingOperator; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public List<String> getFilters() { return filters; }
    public void setFilters(List<String> filters) { this.filters = filters; }
}