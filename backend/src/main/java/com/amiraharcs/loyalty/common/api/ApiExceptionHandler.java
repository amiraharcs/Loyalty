package com.amiraharcs.loyalty.common.api;

import java.util.Comparator;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** MVC framework failures and reusable application exceptions share safe Problem JSON. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return response(ApiErrorCode.VALIDATION_ERROR,
                List.of(fieldError(exception.getParameterName(), "NotNull")), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception instanceof MethodArgumentTypeMismatchException argument) {
            return response(ApiErrorCode.VALIDATION_ERROR,
                    List.of(fieldError(argument.getName(), "Pattern")), headers, request);
        }
        return handleExceptionInternal(exception, null, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getCode())).toList();
        return response(ApiErrorCode.VALIDATION_ERROR, fields, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Invalid server return values are implementation errors, never client validation errors.
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        var fields = exception.getParameterValidationResults().stream().flatMap(result -> {
            var parameter = result.getMethodParameter();
            var annotation = parameter.getParameterAnnotation(RequestParam.class);
            String name = annotation != null && !annotation.name().isBlank() ? annotation.name()
                    : annotation != null && !annotation.value().isBlank() ? annotation.value()
                    : parameter.getParameterName() != null ? parameter.getParameterName() : "request";
            return result.getResolvableErrors().stream().map(error -> {
                String field = error instanceof FieldError bindingError ? bindingError.getField() : name;
                String[] codes = error.getCodes();
                String constraint = codes != null && codes.length > 0 ? codes[codes.length - 1] : null;
                return fieldError(field, constraint);
            });
        }).toList();
        return response(ApiErrorCode.VALIDATION_ERROR, fields, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Discard framework detail/message bodies, which can contain rejected values or internals.
        ApiErrorCode code = switch (status.value()) {
            case 400 -> exception instanceof org.springframework.http.converter.HttpMessageNotReadableException
                    ? ApiErrorCode.MALFORMED_REQUEST : ApiErrorCode.VALIDATION_ERROR;
            case 401 -> ApiErrorCode.UNAUTHENTICATED;
            case 403 -> ApiErrorCode.FORBIDDEN;
            case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ApiErrorCode.NOT_ACCEPTABLE;
            case 409 -> ApiErrorCode.CONFLICT;
            case 413 -> ApiErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ApiErrorCode.RATE_LIMITED;
            case 503 -> ApiErrorCode.SERVICE_UNAVAILABLE;
            default -> ApiErrorCode.INTERNAL_ERROR;
        };
        if (code.status().is5xxServerError()) { logFailure(exception, request); }
        return response(code, List.of(), headers, request);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<Object> serviceValidation(jakarta.validation.ConstraintViolationException exception, WebRequest request) {
        boolean invalidReturn = exception.getConstraintViolations().stream().anyMatch(violation -> {
            for (var node : violation.getPropertyPath()) {
                if (node.getKind() == jakarta.validation.ElementKind.RETURN_VALUE) { return true; }
            }
            return false;
        });
        if (invalidReturn) {
            logFailure(exception, request);
            return response(ApiErrorCode.INTERNAL_ERROR, List.of(), HttpHeaders.EMPTY, request);
        }
        var fields = exception.getConstraintViolations().stream().map(violation -> {
            String field = "request";
            for (var node : violation.getPropertyPath()) {
                if (node.getName() != null) { field = node.getName(); }
            }
            return fieldError(field, violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
        }).toList();
        return response(ApiErrorCode.VALIDATION_ERROR, fields, HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<Object> rateLimit(RateLimitException exception, WebRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter()));
        return response(ApiErrorCode.RATE_LIMITED, List.of(), headers, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException exception, WebRequest request) {
        return response(ApiErrorCode.RESOURCE_NOT_FOUND, List.of(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException exception, WebRequest request) {
        return response(ApiErrorCode.VALIDATION_ERROR, List.of(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException exception, WebRequest request) {
        return response(exception.code(), List.of(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(AuthenticationException exception, WebRequest request) {
        return response(ApiErrorCode.UNAUTHENTICATED, List.of(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException exception, WebRequest request) {
        return response(ApiErrorCode.FORBIDDEN, List.of(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        logFailure(exception, request);
        return response(ApiErrorCode.INTERNAL_ERROR, List.of(), HttpHeaders.EMPTY, request);
    }

    private static ResponseEntity<Object> response(ApiErrorCode code, List<ApiError.FieldError> fields,
            HttpHeaders originalHeaders, WebRequest request) {
        var servlet = (ServletWebRequest) request;
        if (servlet.getResponse() != null && servlet.getResponse().isCommitted()) {
            return null; // A failed/closed response cannot safely be rewritten.
        }
        HttpServletRequest servletRequest = servlet.getRequest();
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders); // Preserve protocol headers such as Allow and Retry-After.
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl("no-store");
        headers.set(RequestIdFilter.HEADER, RequestIdFilter.requestId(servletRequest));
        var sorted = fields.stream().distinct().sorted(Comparator.comparing(ApiError.FieldError::field)
                .thenComparing(ApiError.FieldError::code)).toList();
        return new ResponseEntity<>(ApiError.of(code, servletRequest, sorted), headers, code.status());
    }

    private static ApiError.FieldError fieldError(String field, String constraint) {
        // Never echo custom validator messages: interpolation can reveal a rejected secret.
        return switch (constraint == null ? "" : constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> new ApiError.FieldError(field, "REQUIRED", "This field is required.");
            case "Email", "Pattern" -> new ApiError.FieldError(field, "INVALID_FORMAT", "Enter a valid value.");
            case "SecurePassword" -> new ApiError.FieldError(field, "INVALID_PASSWORD", "Use a permitted password of 15 to 128 characters.");
            case "Size", "Length" -> new ApiError.FieldError(field, "INVALID_SIZE", "The length or size is invalid.");
            case "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Min", "Max", "DecimalMin", "DecimalMax" ->
                    new ApiError.FieldError(field, "OUT_OF_RANGE", "The value is outside the allowed range.");
            default -> new ApiError.FieldError(field, "INVALID_VALUE", "This field is invalid.");
        };
    }

    private static void logFailure(Exception exception, WebRequest request) {
        // Avoid exception messages/stack traces in logs too: they can contain SQL, PII or secrets.
        LOG.error("API failure requestId={} exceptionType={}",
                RequestIdFilter.requestId(((ServletWebRequest) request).getRequest()), exception.getClass().getName());
    }
}
