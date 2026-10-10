package com.amiraharcs.loyalty.common.api;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Client decisions use these stable codes, never exception messages. */
public enum ApiErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed.", "Validation error"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body is missing or invalid.", "Malformed request"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found.", "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current resource state.", "Conflict"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required or credentials are invalid.", "Unauthenticated"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not permitted to perform this action.", "Forbidden"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this resource.", "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported.", "Unsupported media type"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested response format is not supported.", "Not acceptable"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request body is too large.", "Payload too large"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.", "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", "Internal server error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable.", "Service unavailable");

    private final HttpStatus status;
    private final String detail;
    private final String title;

    ApiErrorCode(HttpStatus status, String detail, String title) {
        this.status = status;
        this.detail = detail;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String detail() { return detail; }
    public String title() { return title; }
    public String type() { return "urn:loyalty:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-'); }
}
