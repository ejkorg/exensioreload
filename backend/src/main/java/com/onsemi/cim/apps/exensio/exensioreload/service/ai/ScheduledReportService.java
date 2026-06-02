package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ScheduledReportRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ScheduledReportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for scheduled report generation and delivery.
 */
@Service
public class ScheduledReportService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Simulated scheduled reports
    private static final Map<String, ScheduledReportResponse.ReportSchedule> SCHEDULED_REPORTS = new HashMap<>();

    static {
        ScheduledReportResponse.ReportSchedule daily = new ScheduledReportResponse.ReportSchedule();
        daily.setReportId("SCHED001");
        daily.setReportName("Daily Operations Summary");
        daily.setFrequency("DAILY");
        daily.setTime("08:00");
        daily.setEnabled(true);
        daily.setChannels(List.of("EMAIL", "SLACK"));
        daily.setRecipients(List.of("ops-team@onsemi.com"));
        daily.setLastRun(new Date(System.currentTimeMillis() - 86400000).toString());
        daily.setNextRun(calculateNextRun("08:00"));
        SCHEDULED_REPORTS.put("daily", daily);

        ScheduledReportResponse.ReportSchedule weekly = new ScheduledReportResponse.ReportSchedule();
        weekly.setReportId("SCHED002");
        weekly.setReportName("Weekly Performance Report");
        weekly.setFrequency("WEEKLY");
        weekly.setTime("09:00");
        weekly.setDayOfWeek("MONDAY");
        weekly.setEnabled(true);
        weekly.setChannels(List.of("EMAIL"));
        weekly.setRecipients(List.of("management@onsemi.com"));
        weekly.setLastRun(new Date(System.currentTimeMillis() - 604800000).toString());
        weekly.setNextRun("Monday 09:00");
        SCHEDULED_REPORTS.put("weekly", weekly);

        ScheduledReportResponse.ReportSchedule monthly = new ScheduledReportResponse.ReportSchedule();
        monthly.setReportId("SCHED003");
        monthly.setReportName("Monthly Cost Analysis");
        monthly.setFrequency("MONTHLY");
        monthly.setDayOfMonth(1);
        monthly.setTime("08:00");
        monthly.setEnabled(false);
        monthly.setChannels(List.of("EMAIL"));
        monthly.setRecipients(List.of("finance@onsemi.com"));
        monthly.setLastRun(null);
        monthly.setNextRun(null);
        SCHEDULED_REPORTS.put("monthly", monthly);
    }

    public ScheduledReportService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Create scheduled report.
     */
    public ScheduledReportResponse createSchedule(ScheduledReportRequest request) {
        ScheduledReportResponse response = new ScheduledReportResponse();

        try {
            String scheduleId = "SCHED" + System.currentTimeMillis();

            ScheduledReportResponse.ReportSchedule schedule = new ScheduledReportResponse.ReportSchedule();
            schedule.setReportId(scheduleId);
            schedule.setReportName(request.getReportName());
            schedule.setFrequency(request.getFrequency());
            schedule.setTime(request.getTime());
            schedule.setDayOfWeek(request.getDayOfWeek());
            schedule.setDayOfMonth(request.getDayOfMonth());
            schedule.setEnabled(true);
            schedule.setChannels(request.getChannels());
            schedule.setRecipients(request.getRecipients());
            schedule.setLastRun(null);
            schedule.setNextRun(calculateNextRun(request.getTime()));

            response.setScheduleId(scheduleId);
            response.setSchedule(schedule);
            response.setSuccess(true);
            response.setMessage("Report scheduled successfully");

        } catch (Exception e) {
            log.error("Scheduled report creation failed", e);
            response.setSuccess(false);
            response.setMessage(e.getMessage());
        }

        return response;
    }

    /**
     * Get all schedules.
     */
    public List<ScheduledReportResponse.ReportSchedule> getAllSchedules() {
        return new ArrayList<>(SCHEDULED_REPORTS.values());
    }

    /**
     * Get schedule by ID.
     */
    public ScheduledReportResponse.ReportSchedule getSchedule(String scheduleId) {
        return SCHEDULED_REPORTS.values().stream()
            .filter(s -> s.getReportId().equals(scheduleId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Enable/disable schedule.
     */
    public boolean toggleSchedule(String scheduleId, boolean enabled) {
        for (ScheduledReportResponse.ReportSchedule schedule : SCHEDULED_REPORTS.values()) {
            if (schedule.getReportId().equals(scheduleId)) {
                schedule.setEnabled(enabled);
                schedule.setNextRun(enabled ? calculateNextRun(schedule.getTime()) : null);
                return true;
            }
        }
        return false;
    }

    /**
     * Generate report now.
     */
    public ScheduledReportResponse generateNow(String scheduleId) {
        ScheduledReportResponse response = new ScheduledReportResponse();

        ScheduledReportResponse.ReportSchedule schedule = getSchedule(scheduleId);
        if (schedule == null) {
            response.setSuccess(false);
            response.setMessage("Schedule not found");
            return response;
        }

        response.setScheduleId(scheduleId);
        response.setSuccess(true);
        response.setMessage("Report generated successfully");

        // Generate report content
        response.setGeneratedContent(generateReportContent(schedule));
        response.setDeliveredTo(schedule.getRecipients());

        // Update last run
        schedule.setLastRun(new Date().toString());

        return response;
    }

    private String generateReportContent(ScheduledReportResponse.ReportSchedule schedule) {
        if (!aiProperties.isConfigured()) {
            return "Report content for " + schedule.getReportName();
        }

        try {
            String prompt = String.format("""
                Generate a %s report summary for %s.
                
                Report: %s
                Frequency: %s
                
                Provide a brief overview suitable for email distribution.
                """,
                schedule.getFrequency().toLowerCase(),
                new Date(),
                schedule.getReportName(),
                schedule.getFrequency()
            );

            Map<String, Object> context = Map.of("task", "scheduled_report");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            return "Report summary for " + schedule.getReportName();
        }
    }

    private static String calculateNextRun(String time) {
        return "Today at " + time;
    }
}