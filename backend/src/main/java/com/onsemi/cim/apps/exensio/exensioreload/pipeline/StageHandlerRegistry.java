package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registry for stage handlers that delegate stage completion checks to handler implementations.
 * 
 * Uses a registry pattern to map StageType to StageHandler implementations. Handlers are
 * auto-registered by the Spring container when annotated with @Component. This enables
 * extensibility - new stage types can be added by implementing StageHandler and registering
 * with Spring.
 * 
 * Requirements: 8.1, 8.2
 */
@Component
public class StageHandlerRegistry {
    private static final Logger log = LoggerFactory.getLogger(StageHandlerRegistry.class);

    private final Map<StageType, StageHandler> handlersByType = new ConcurrentHashMap<>();

    /**
     * Auto-register all StageHandler implementations from the Spring context.
     * 
     * Called automatically during component initialization via @Autowired with List injection.
     * This allows new handlers to be discovered and registered automatically when they're
     * added as Spring components.
     * 
     * @param handlers all StageHandler beans from the Spring context
     */
    @Autowired(required = false)
    public void autoRegisterHandlers(List<StageHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            log.warn("No StageHandler implementations found in Spring context - pipeline will use legacy mode");
            return;
        }

        for (StageHandler handler : handlers) {
            try {
                registerHandler(handler.getStageType(), handler);
            } catch (Exception e) {
                log.error("Failed to register stage handler: {}", handler.getClass().getName(), e);
            }
        }

        log.info("Auto-registered {} stage handlers", handlersByType.size());
    }

    /**
     * Register a stage handler for a specific stage type.
     * 
     * Called by Spring when StageHandler beans are created. Also available for manual
     * registration during testing.
     * 
     * @param stageType the stage type this handler supports
     * @param handler the handler implementation
     * @throws IllegalArgumentException if a handler is already registered for this type
     */
    public void registerHandler(StageType stageType, StageHandler handler) {
        if (stageType == null) {
            throw new IllegalArgumentException("stageType must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        StageHandler previous = handlersByType.putIfAbsent(stageType, handler);
        if (previous != null) {
            throw new IllegalArgumentException(
                "Handler for stage type " + stageType + " is already registered: " + previous.getClass().getName());
        }

        log.debug("Registered stage handler for type {}: {}", stageType, handler.getClass().getName());
    }

    /**
     * Get a handler for a specific stage type.
     * 
     * @param stageType the stage type
     * @return the registered handler for this type
     * @throws StageHandlerNotFoundException if no handler is registered for this type
     */
    public StageHandler getHandler(StageType stageType) {
        if (stageType == null) {
            throw new IllegalArgumentException("stageType must not be null");
        }

        StageHandler handler = handlersByType.get(stageType);
        if (handler == null) {
            throw new StageHandlerNotFoundException(
                "No handler registered for stage type: " + stageType + 
                ". Registered types: " + handlersByType.keySet());
        }

        return handler;
    }

    /**
     * Check if a handler is registered for a specific stage type.
     * 
     * @param stageType the stage type
     * @return true if a handler is registered
     */
    public boolean hasHandler(StageType stageType) {
        return handlersByType.containsKey(stageType);
    }

    /**
     * Get all registered stage handlers.
     * 
     * @return collection of all registered handlers
     */
    public Collection<StageHandler> getAllHandlers() {
        return handlersByType.values();
    }

    /**
     * Get all supported stage types.
     * 
     * @return list of stage types with registered handlers
     */
    public List<StageType> getSupportedStageTypes() {
        return List.copyOf(handlersByType.keySet());
    }

    /**
     * Get the number of registered handlers.
     * 
     * @return count of registered handlers
     */
    public int getHandlerCount() {
        return handlersByType.size();
    }

    /**
     * Clear all registered handlers.
     * 
     * Useful for testing to reset registry state.
     */
    protected void clearHandlers() {
        handlersByType.clear();
        log.debug("Cleared all stage handlers from registry");
    }

    /**
     * Exception thrown when a required handler is not found in the registry.
     */
    public static class StageHandlerNotFoundException extends RuntimeException {
        public StageHandlerNotFoundException(String message) {
            super(message);
        }

        public StageHandlerNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
