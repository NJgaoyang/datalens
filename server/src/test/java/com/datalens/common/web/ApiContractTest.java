package com.datalens.common.web;

import com.datalens.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter()).build();
    }

    @Test void requestIdIsServerOwnedAndMatchesEnvelope() throws Exception {
        var result = mvc.perform(post("/probe").header("X-Request-ID", "caller-controlled")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn();
        String id = result.getResponse().getHeader("X-Request-ID");
        assertThat(id).matches("[0-9a-f-]{36}").isNotEqualTo("caller-controlled");
        assertThat(result.getResponse().getContentAsString()).contains(id);
    }

    @Test void validationProducesStableBadRequest() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void malformedJsonIsNotInternalError() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test void internalDetailsAreNotReturned() throws Exception {
        var result = mvc.perform(get("/probe/failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-example", "IllegalStateException");
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }

    // Test-only controller; no echo or failure route is present in production.
    @RestController static class ProbeController {
        record Input(@NotBlank String name) {}
        @PostMapping("/probe") ApiResponse<Input> echo(@Valid @RequestBody Input input, HttpServletRequest request) {
            return ApiResponse.success(input, RequestIdFilter.id(request));
        }
        @GetMapping("/probe/failure") void failure() { throw new IllegalStateException("secret-example"); }
    }
}
