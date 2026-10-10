package com.amiraharcs.loyalty.common.api;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Security failures occur before MVC advice and must use the same contract. */
@Component
public class ApiErrorWriter {
    private final JsonMapper mapper;

    public ApiErrorWriter(JsonMapper mapper) { this.mapper = mapper; }

    public void write(ApiErrorCode code, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (response.isCommitted()) { return; }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(RequestIdFilter.HEADER, RequestIdFilter.requestId(request));
        mapper.writeValue(response.getOutputStream(), ApiError.of(code, request, List.of()));
    }
}
