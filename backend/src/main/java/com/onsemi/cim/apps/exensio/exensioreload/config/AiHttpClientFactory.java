package com.onsemi.cim.apps.exensio.exensioreload.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Factory for creating and sharing HttpClient instances for AI provider APIs.
 * 
 * <p>Provides a dedicated HttpClient bean for communicating with external AI services
 * (Anthropic Claude, OpenAI, Groq, Ollama, etc.), separate from Exensio and Elasticsearch clients.</p>
 * 
 * <p>Configuration:
 * <ul>
 *   <li>Redirect policy: NORMAL (AI APIs may use redirects for load balancing)</li>
 *   <li>Connect timeout: 15 seconds (AI services may have slower initial handshakes)</li>
 * </ul>
 * 
 * <p>Feature: AI Integration, Property: Dedicated HttpClient for AI Providers</p>
 */
@Configuration
public class AiHttpClientFactory {

    /**
     * Creates a dedicated HttpClient bean for AI provider APIs.
     * 
     * @return shared HttpClient instance for AI API calls
     */
    @Bean(name = "aiHttpClient")
    public HttpClient aiHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }
}
