package com.certmonitor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * Serves index.html for all non-API routes so the React SPA handles its own navigation.
 * Static assets (/assets/*, favicon.svg, etc.) are served by Spring's ResourceHttpRequestHandler
 * before this controller is reached.
 */
@Controller
public class SpaController {

    private final ResourceLoader resourceLoader;

    public SpaController(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @GetMapping(value = { "/", "/{path:[^\\.]*}", "/{path:[^\\.]*}/**" })
    @ResponseBody
    public ResponseEntity<Resource> spa(HttpServletRequest request) throws IOException {
        Resource index = resourceLoader.getResource("classpath:/static/index.html");
        if (!index.exists()) {
            index = resourceLoader.getResource("file:../frontend/dist/index.html");
        }
        if (!index.exists()) {
            index = resourceLoader.getResource("file:./frontend/dist/index.html");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(index);
    }
}
