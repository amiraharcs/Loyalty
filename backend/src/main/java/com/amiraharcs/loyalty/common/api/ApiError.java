package com.amiraharcs.loyalty.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

/** RFC 9457 standard members plus the extensions agreed in the architecture. */
public record ApiError(String type, String title, int status, String detail,
                       String instance, String code, String requestId,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> fieldErrors) {
    public ApiError {
        fieldErrors = List.copyOf(fieldErrors);
    }

    public static ApiError of(ApiErrorCode code, HttpServletRequest request, List<FieldError> errors) {
        return new ApiError(code.type(), code.title(), code.status().value(), code.detail(),
                request.getRequestURI(), code.name(), RequestIdFilter.requestId(request), errors);
    }

    public record FieldError(String field, String code, String message) { }
}
