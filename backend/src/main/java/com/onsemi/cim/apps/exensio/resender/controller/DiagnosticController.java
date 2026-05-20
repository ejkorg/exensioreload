package com.onsemi.cim.apps.exensio.resender.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/diagnostic")
public class DiagnosticController {

    @Autowired
    private ApplicationContext applicationContext;

    @GetMapping("/beans")
    public ResponseEntity<Map<String, Object>> getBeans() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        
        Map<String, Object> response = Map.of(
            "totalBeans", beanNames.length,
            "userAdminController", Arrays.stream(beanNames).anyMatch(name -> name.contains("userAdminController")),
            "userManagementService", Arrays.stream(beanNames).anyMatch(name -> name.contains("userManagementService")),
            "appUserRepository", Arrays.stream(beanNames).anyMatch(name -> name.contains("appUserRepository")),
            "auditService", Arrays.stream(beanNames).anyMatch(name -> name.contains("auditService")),
            "relevantBeans", Arrays.stream(beanNames)
                .filter(name -> name.toLowerCase().contains("user") || name.toLowerCase().contains("admin"))
                .collect(Collectors.toList())
        );
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/auth")
    public ResponseEntity<Map<String, Object>> getAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        Map<String, Object> response = Map.of(
            "authenticated", auth != null && auth.isAuthenticated(),
            "principal", auth != null ? auth.getName() : "null",
            "authorities", auth != null ? auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toList()) : "null"
        );
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/endpoints")
    public ResponseEntity<Map<String, Object>> getEndpoints() {
        try {
            // Try to get the UserAdminController bean
            Object userAdminController = applicationContext.getBean("userAdminController");
            
            Map<String, Object> response = Map.of(
                "userAdminControllerExists", userAdminController != null,
                "userAdminControllerClass", userAdminController.getClass().getName(),
                "message", "UserAdminController bean found successfully"
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = Map.of(
                "userAdminControllerExists", false,
                "error", e.getMessage(),
                "message", "UserAdminController bean not found"
            );
            
            return ResponseEntity.ok(response);
        }
    }
}
