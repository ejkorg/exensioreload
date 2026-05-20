package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.CrontabJob;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CrontabJobMatcher — verifies port matching logic (Requirements 4.3, 4.4).
 */
class CrontabJobMatcherTest {

    private CrontabJobMatcher matcher = new CrontabJobMatcher();

    @Test
    void match_returnsJob_whenCommandContainsPort() {
        // Requirement 4.3: find crontab job containing the sender port in the command
        List<CrontabJob> jobs = createTestJobs();

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("8080");

        result = matcher.match(jobs, 9090);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("9090");
    }

    @Test
    void match_returnsNull_whenNoJobContainsPort() {
        // Requirement 4.4: return null when no matching job found
        List<CrontabJob> jobs = createTestJobs();

        CrontabJob result = matcher.match(jobs, 12345);
        assertThat(result).isNull();
    }

    @Test
    void match_returnsNull_whenJobsListIsEmpty() {
        List<CrontabJob> jobs = Arrays.asList();

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNull();
    }

    @Test
    void match_returnsNull_whenJobsListIsNull() {
        CrontabJob result = matcher.match(null, 8080);
        assertThat(result).isNull();
    }

    @Test
    void match_returnsNull_whenSenderPortIsNull() {
        List<CrontabJob> jobs = createTestJobs();

        CrontabJob result = matcher.match(jobs, null);
        assertThat(result).isNull();
    }

    @Test
    void match_returnsNull_whenJobsAreNull() {
        List<CrontabJob> jobs = Arrays.asList(
                null,
                createJob("* * * * *", "java -jar cp.jar --port 8080"),
                null
        );

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("8080");
    }

    @Test
    void match_returnsNull_whenJobCommandIsNull() {
        List<CrontabJob> jobs = Arrays.asList(
                createJob("* * * * *", null),
                createJob("* * * * *", "java -jar cp.jar --port 8080")
        );

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("8080");
    }

    @Test
    void match_matchesPortInDifferentPositions() {
        // Port can appear anywhere in the command
        List<CrontabJob> jobs = Arrays.asList(
                createJob("* * * * *", "java -jar cp.jar --port 8080"),
                createJob("* * * * *", "/opt/cp/run.sh 9090"),
                createJob("* * * * *", "cp --input 1234 --port 5678")
        );

        CrontabJob result = matcher.match(jobs, 5678);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("5678");
    }

    @Test
    void match_matchesPortAsArgument() {
        // Port as a simple argument
        List<CrontabJob> jobs = Arrays.asList(
                createJob("* * * * *", "/opt/cp/run.sh 8080"),
                createJob("* * * * *", "/opt/cp/run.sh 9090")
        );

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("8080");
    }

    @Test
    void match_matchesPortInOption() {
        // Port in an option like --port 8080
        List<CrontabJob> jobs = Arrays.asList(
                createJob("* * * * *", "java -jar cp.jar --port 8080"),
                createJob("* * * * *", "java -jar cp.jar --port 9090")
        );

        CrontabJob result = matcher.match(jobs, 9090);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("9090");
    }

    @Test
    void match_returnsFirstMatch_whenMultipleJobsContainPort() {
        // When multiple jobs contain the same port, return the first match
        List<CrontabJob> jobs = Arrays.asList(
                createJob("* * * * *", "java -jar cp.jar --port 8080"),
                createJob("* * * * *", "java -jar cp.jar --port 8080")
        );

        CrontabJob result = matcher.match(jobs, 8080);
        assertThat(result).isNotNull();
        assertThat(result.getCommand()).contains("8080");
    }

    private List<CrontabJob> createTestJobs() {
        return Arrays.asList(
                createJob("* * * * *", "java -jar cp.jar --port 8080"),
                createJob("0 * * * *", "/opt/cp/run.sh 9090"),
                createJob("*/5 * * * *", "cp --input 1234 --port 5678")
        );
    }

    private CrontabJob createJob(String schedule, String command) {
        CrontabJob job = new CrontabJob();
        job.setSchedule(schedule);
        job.setCommand(command);
        return job;
    }
}
