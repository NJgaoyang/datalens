package com.datalens.foundation;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serve the same Vue application on a single HTTP port, including deep links after refresh. */
@Controller
public class SpaController {
    @GetMapping({
        "/", "/login", "/workspace", "/system", "/account/password", "/audit",
        "/ask", "/ask/{conversationId:[A-Za-z0-9-]+}",
        "/charts", "/charts/{chartId:[A-Za-z0-9-]+}",
        "/sources", "/queries", "/semantics", "/subjects", "/model", "/settings"
    })
    public String index() {
        return "forward:/index.html";
    }
}
