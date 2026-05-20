package com.onsemi.cim.apps.exensio.resender.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the sender port number from Elasticsearch sender configuration names.
 * <p>
 * The cpConfig field can contain various formats like:
 * <ul>
 *   <li>"64000_POWERCHIP_WAT_TO_COLO_SENDER" (port at start)</li>
 *   <li>"POWERCHIP_64000_WAT_TO_COLO_SENDER" (port in middle)</li>
 *   <li>"POWERCHIP_WAT_TO_COLO_SENDER_64000" (port at end)</li>
 * </ul>
 * <p>
 * The port number is always separated by underscores and consists of digits.
 * <p>
 * Requirements: 1.3, 4.1, 4.2
 */
@Service
public class SenderPortExtractor {
    private static final Logger logger = LoggerFactory.getLogger(SenderPortExtractor.class);

    /**
     * Regex pattern to extract port number from config name.
     * Matches a sequence of digits that is either:
     * - At the start of the string followed by underscore
     * - Between underscores
     * - At the end of the string preceded by underscore
     */
    private static final Pattern PORT_PATTERN = Pattern.compile("(?<![0-9_])([0-9]{4,5})(?![0-9_])");

    /**
     * Extracts the sender port number from an Elasticsearch sender configuration name.
     * <p>
     * The cpConfig field can contain various formats like:
     * <ul>
     *   <li>"64000_POWERCHIP_WAT_TO_COLO_SENDER" (port at start)</li>
     *   <li>"POWERCHIP_64000_WAT_TO_COLO_SENDER" (port in middle)</li>
     *   <li>"POWERCHIP_WAT_TO_COLO_SENDER_64000" (port at end)</li>
     * </ul>
     * <p>
     * The port number is always separated by underscores and consists of digits.
     *
     * @param senderConfigName the sender configuration name from Elasticsearch (cpConfig field)
     * @return the extracted port number, or empty if not found
     */
    public Optional<Integer> extractPort(String senderConfigName) {
        if (senderConfigName == null || senderConfigName.trim().isEmpty()) {
            logger.debug("Sender config name is null or empty");
            return Optional.empty();
        }

        String trimmed = senderConfigName.trim();

        // Try to find a port number pattern in the string
        Matcher matcher = PORT_PATTERN.matcher(trimmed);

        while (matcher.find()) {
            try {
                int port = Integer.parseInt(matcher.group(1));
                logger.debug("Extracted port {} from sender config name '{}'", port, senderConfigName);
                return Optional.of(port);
            } catch (NumberFormatException e) {
                // Continue searching for other potential port numbers
            }
        }

        logger.debug("No port found in sender config name '{}'", senderConfigName);
        return Optional.empty();
    }
}
