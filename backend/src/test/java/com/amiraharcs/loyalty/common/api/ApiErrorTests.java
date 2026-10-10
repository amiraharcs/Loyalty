package com.amiraharcs.loyalty.common.api;

import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiErrorTests.FixtureController.class)
@Import({ApiErrorTests.FixtureController.class, ApiExceptionHandler.class, ApiErrorWriter.class, RequestIdFilter.class, ApiErrorTests.SecurityConfiguration.class})
class ApiErrorTests {
    private static final String VALID = "{\"name\":\"Test\",\"email\":\"test@example.org\",\"label\":\"valid\",\"count\":1}";
    private static final String SECRET = "secret-password SQL SELECT private_table jdbc:postgresql";
    @Autowired MockMvc mvc;

    @Test
    void validRequestSucceedsWithServerGeneratedCorrelationId() throws Exception {
        var result = mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID).header(RequestIdFilter.HEADER, "untrusted-client-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("accepted").value(true)).andReturn();
        assertThat(UUID.fromString(result.getResponse().getHeader(RequestIdFilter.HEADER))).isNotNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void invalidFieldsHaveStableCodesWithoutRejectedValuesOrCustomMessages() throws Exception {
        problem(mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"secret-password\",\"label\":\"x\",\"count\":0}")),
                400, "VALIDATION_ERROR", "/_test/body")
                .andExpect(jsonPath("fieldErrors.length()").value(4))
                .andExpect(jsonPath("fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("fieldErrors[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("fieldErrors[1].code").value("INVALID_FORMAT"))
                .andExpect(jsonPath("fieldErrors[2].code").value("INVALID_SIZE"))
                .andExpect(jsonPath("fieldErrors[3].code").value("REQUIRED"));
    }

    @Test
    void missingBodyFieldsAreValidated() throws Exception {
        problem(mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")),
                400, "VALIDATION_ERROR", "/_test/body").andExpect(jsonPath("fieldErrors").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({"'{',MALFORMED_REQUEST", "'[]',MALFORMED_REQUEST", "'',MALFORMED_REQUEST",
            "'{\"count\":\"secret-password\"}',MALFORMED_REQUEST"})
    void malformedMissingOrWrongTypeBodiesAreSafe(String body, String code) throws Exception {
        problem(mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body)), 400, code, "/_test/body");
    }

    @Test
    void unknownMutationFieldsAreRejected() throws Exception {
        problem(mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("}", ",\"role\":\"ADMIN\"}"))), 400, "MALFORMED_REQUEST", "/_test/body");
    }

    @ParameterizedTest
    @CsvSource({"not-found,404,RESOURCE_NOT_FOUND", "conflict,409,CONFLICT", "authentication,401,UNAUTHENTICATED",
            "forbidden,403,FORBIDDEN", "unexpected,500,INTERNAL_ERROR", "framework,404,RESOURCE_NOT_FOUND"})
    void mapsExceptionsWithoutLeakingInternalDetails(String kind, int status, String code) throws Exception {
        problem(mvc.perform(get("/_test/failure/" + kind)), status, code, "/_test/failure/" + kind);
    }

    @Test
    void missingRequiredParameterIsBadRequest() throws Exception {
        problem(mvc.perform(get("/_test/parameter")), 400, "VALIDATION_ERROR", "/_test/parameter");
    }

    @Test
    void invalidParameterTypeIsBadRequestAndDoesNotEchoTheValue() throws Exception {
        problem(mvc.perform(get("/_test/parameter").param("count", "secret-password")),
                400, "VALIDATION_ERROR", "/_test/parameter");
    }

    @Test
    void validParameterIsAccepted() throws Exception {
        mvc.perform(get("/_test/parameter").param("count", "1"))
                .andExpect(status().isOk()).andExpect(content().string("1"));
    }

    @Test
    void invalidControllerReturnValuesAreInternalErrors() throws Exception {
        problem(mvc.perform(get("/_test/invalid-return")), 500, "INTERNAL_ERROR", "/_test/invalid-return");
    }

    @Test
    void controllerParameterConstraintsAreValidated() throws Exception {
        problem(mvc.perform(get("/_test/parameter").param("count", "0")),
                400, "VALIDATION_ERROR", "/_test/parameter")
                .andExpect(jsonPath("fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("fieldErrors[0].code").value("OUT_OF_RANGE"));
    }

    @Test
    void unknownRouteUsesTheSameNotFoundContract() throws Exception {
        problem(mvc.perform(get("/_test/absent")), 404, "RESOURCE_NOT_FOUND", "/_test/absent");
    }

    @Test
    void unsupportedMethodKeepsAllowHeader() throws Exception {
        problem(mvc.perform(put("/_test/body").with(csrf())), 405, "METHOD_NOT_ALLOWED", "/_test/body")
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    void unsupportedContentTypeIsMapped() throws Exception {
        problem(mvc.perform(post("/_test/body").with(csrf()).contentType(MediaType.TEXT_PLAIN).content(SECRET)),
                415, "UNSUPPORTED_MEDIA_TYPE", "/_test/body");
    }

    @Test
    void securityAuthenticationEntryPointUsesProblemJsonWithoutRedirect() throws Exception {
        problem(mvc.perform(get("/_test/security/authenticated")), 401, "UNAUTHENTICATED", "/_test/security/authenticated")
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void authenticatedPermissionFailureUsesProblemJson() throws Exception {
        problem(mvc.perform(get("/_test/security/forbidden").with(user("test").roles("CUSTOMER"))),
                403, "FORBIDDEN", "/_test/security/forbidden");
    }

    @Test
    void missingCsrfIsForbiddenEvenOnAnOtherwiseAllowedEndpoint() throws Exception {
        problem(mvc.perform(post("/_test/body").contentType(MediaType.APPLICATION_JSON).content(VALID)),
                403, "FORBIDDEN", "/_test/body");
    }

    @Test
    void replacesIncomingIdsAndOmitsQueryStringsFromInstance() throws Exception {
        var result = problem(mvc.perform(get("/_test/failure/not-found").param("private", "secret-password")
                        .header(RequestIdFilter.HEADER, "client-id")),
                404, "RESOURCE_NOT_FOUND", "/_test/failure/not-found").andReturn();
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotEqualTo("client-id");
        var next = mvc.perform(get("/_test/absent")).andReturn();
        assertThat(next.getResponse().getHeader(RequestIdFilter.HEADER))
                .isNotEqualTo(result.getResponse().getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get("requestId")).isNull();
    }

    private ResultActions problem(ResultActions result, int status, String code, String path) throws Exception {
        result.andExpect(status().is(status)).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("code").value(code)).andExpect(jsonPath("status").value(status))
                .andExpect(jsonPath("instance").value(path)).andExpect(jsonPath("title").isNotEmpty())
                .andExpect(jsonPath("detail").isNotEmpty()).andExpect(jsonPath("type").value(
                        "urn:loyalty:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')))
                .andExpect(jsonPath("stackTrace").doesNotExist()).andExpect(jsonPath("trace").doesNotExist())
                .andExpect(jsonPath("exception").doesNotExist());
        var response = result.andReturn().getResponse();
        String id = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
        result.andExpect(jsonPath("requestId").value(id));
        assertThat(response.getContentAsString()).doesNotContain("secret-password", "SQL", "SELECT", "jdbc:",
                "private_table", "IllegalStateException", "validatedValue", "leaky-validator-message", "rejectedValue");
        return result;
    }

    /** Fixtures exist only on the test classpath; no product endpoints are added. */
    @RestController
    static class FixtureController {
        @PostMapping("/_test/body")
        Map<String, Boolean> body(@Valid @RequestBody FixtureRequest request) { return Map.of("accepted", true); }

        @GetMapping("/_test/parameter")
        int parameter(@RequestParam("count") @Positive int count) { return count; }

        @GetMapping("/_test/invalid-return")
        @Positive
        int invalidReturn() { return -1; }

        @GetMapping("/_test/failure/{kind}")
        void failure(@PathVariable String kind) {
            throw switch (kind) {
                case "not-found" -> new ResourceNotFoundException();
                case "conflict" -> new ConflictException();
                case "authentication" -> new BadCredentialsException(SECRET);
                case "forbidden" -> new AccessDeniedException(SECRET);
                case "framework" -> new ResponseStatusException(HttpStatus.NOT_FOUND, SECRET);
                default -> new IllegalStateException(SECRET);
            };
        }
    }

    record FixtureRequest(@NotBlank String name, @NotBlank @Email(message = "leaky-validator-message ${validatedValue}") String email,
                          @NotNull @Size(min = 3, max = 8) String label, @NotNull @Positive Integer count) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityConfiguration {
        @Bean
        SecurityFilterChain fixtureSecurity(HttpSecurity http, ApiErrorWriter errors) throws Exception {
            return http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/_test/security/authenticated").authenticated()
                            .requestMatchers("/_test/security/forbidden").hasRole("ADMIN")
                            .anyRequest().permitAll())
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint((req, res, ex) -> errors.write(ApiErrorCode.UNAUTHENTICATED, req, res))
                            .accessDeniedHandler((req, res, ex) -> errors.write(ApiErrorCode.FORBIDDEN, req, res)))
                    .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).build();
        }
    }
}
