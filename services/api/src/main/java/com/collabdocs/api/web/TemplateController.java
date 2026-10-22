package com.collabdocs.api.web;

import com.collabdocs.api.docs.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemplateController {
    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/api/v1/templates")
    Map<String, Object> list(HttpServletRequest request, @RequestParam(required = false) UUID workspaceId) {
        return templates.list(AuthController.current(request).userId(), workspaceId);
    }
}
