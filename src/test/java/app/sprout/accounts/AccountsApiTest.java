package app.sprout.accounts;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Opening accounts on a real Postgres against a stand-in Sprout Bank, checked against accounts-v1.yaml. */
@Testcontainers
@SpringBootTest(properties = "spring.config.name=accounts")
@AutoConfigureMockMvc
class AccountsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    static final java.util.Map<String, String> DEMAT = new java.util.concurrent.ConcurrentHashMap<>();       // clientRef -> boId
    static final java.util.Map<String, String> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();  // clientCode -> boId
    static final java.util.concurrent.atomic.AtomicBoolean DEPOSITORY_DOWN = new java.util.concurrent.atomic.AtomicBoolean();
    static final HttpServer BANK = bank();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=accounts");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.accounts.bank.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
        r.add("sprout.accounts.depository.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
        r.add("sprout.accounts.clearing.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.ACCOUNTS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static int pans = 1000;

    static synchronized String newPan() {
        return "ABCP" + "XYZ".charAt(pans % 3) + String.format("%04d", pans++) + "K";
    }

    ResultActions open(UUID user, Map<String, String> overrides) throws Exception {
        Map<String, String> body = new HashMap<>(Map.of("legalName", "Asha Rao", "dateOfBirth", "1999-04-12",
                "pan", newPan(), "bankVpa", "asha.rao@sproutbank"));
        body.putAll(overrides);
        return mvc.perform(post("/v1/accounts").header("X-User-Id", user.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void anAdultWithAnIndividualPanAndARealUpiAddressGetsAnAccount() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/v1/accounts/me").header("X-User-Id", user.toString()))
                .andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
        open(user, Map.of("pan", "abcpr1234k", "legalName", "  Asha   Rao "))
                .andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.panMasked").value("XXXXX1234K"))
                .andExpect(jsonPath("$.legalName").value("Asha Rao"));
        mvc.perform(get("/v1/accounts/me").header("X-User-Id", user.toString())).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        open(user, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
    }

    @Test
    void everyAccountGetsADematAccountRegisteredForSettlement() throws Exception {
        UUID user = UUID.randomUUID();
        String demat = json.readTree(open(user, Map.of()).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andReturn().getResponse().getContentAsString()).path("dematAccount").asText();
        assertThat(demat).matches("[0-9]{16}");
        assertThat(DEMAT.get(user.toString())).isEqualTo(demat);
        assertThat(REGISTERED.get(user.toString())).as("registered with clearing under the user's id").isEqualTo(demat);
        mvc.perform(get("/internal/v1/accounts/" + user).header("X-Service-Key", "dev-only-service-key"))
                .andExpect(jsonPath("$.dematAccount").value(demat));
    }

    @Test
    void ifTheDepositoryIsAwayNothingIsSavedAndTryingAgainWorks() throws Exception {
        UUID user = UUID.randomUUID();
        String pan = newPan();
        DEPOSITORY_DOWN.set(true);
        try {
            open(user, Map.of("pan", pan)).andExpect(status().isServiceUnavailable()).andExpect(MATCHES_CONTRACT)
                    .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        } finally {
            DEPOSITORY_DOWN.set(false);
        }
        mvc.perform(get("/v1/accounts/me").header("X-User-Id", user.toString())).andExpect(status().isNotFound());
        open(user, Map.of("pan", pan)).andExpect(status().isCreated());
    }

    @Test
    void onePanOneAccount() throws Exception {
        String pan = newPan();
        open(UUID.randomUUID(), Map.of("pan", pan)).andExpect(status().isCreated());
        open(UUID.randomUUID(), Map.of("pan", pan.toLowerCase())).andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("PAN_IN_USE"));
    }

    @Test
    void kycRefusesTheUnderageAndNonIndividualOrMalformedPans() throws Exception {
        open(UUID.randomUUID(), Map.of("dateOfBirth", "2008-10-06")).andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("KYC_REJECTED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("18")));
        open(UUID.randomUUID(), Map.of("dateOfBirth", "2008-10-05")).andExpect(status().isCreated()); // 18 today
        open(UUID.randomUUID(), Map.of("pan", "ABCCR1234K")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("individuals")));
        open(UUID.randomUUID(), Map.of("pan", "12345")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KYC_REJECTED"));
        open(UUID.randomUUID(), Map.of("dateOfBirth", "2099-01-01")).andExpect(status().isBadRequest());
        open(UUID.randomUUID(), Map.of("dateOfBirth", "not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void theUpiAddressMustExistAtTheBank() throws Exception {
        open(UUID.randomUUID(), Map.of("bankVpa", "nobody@sproutbank")).andExpect(status().isUnprocessableEntity())
                .andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("VPA_NOT_FOUND"));
        open(UUID.randomUUID(), Map.of("bankVpa", "not an address")).andExpect(status().isUnprocessableEntity());
        open(UUID.randomUUID(), Map.of("bankVpa", "broken@sproutbank")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void servicesNeedTheirKeyAndUsersNeedToBeSignedIn() throws Exception {
        UUID user = UUID.randomUUID();
        open(user, Map.of()).andExpect(status().isCreated());
        mvc.perform(get("/internal/v1/accounts/" + user)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/accounts/" + user).header("X-Service-Key", "dev-only-service-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bankVpa").value("asha.rao@sproutbank"));
        mvc.perform(get("/v1/accounts/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** A stand-in for Sprout Bank's VPA lookup. */
    static HttpServer bank() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/partner/v1/vpas/", ex -> {
                String vpa = ex.getRequestURI().getPath().substring("/partner/v1/vpas/".length());
                int status = !"dev-only-partner-key".equals(ex.getRequestHeaders().getFirst("X-Partner-Key")) ? 401
                        : vpa.equals("asha.rao@sproutbank") ? 200 : vpa.equals("broken@sproutbank") ? 500 : 404;
                byte[] body = status == 200 ? "{\"vpa\":\"asha.rao@sproutbank\",\"holderName\":\"Asha Rao\"}".getBytes() : new byte[0];
                ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    ex.getResponseBody().write(body);
                }
                ex.close();
            });
            s.createContext("/participant/v1/accounts", ex -> {
                if (DEPOSITORY_DOWN.get()) {
                    ex.sendResponseHeaders(503, -1);
                    ex.close();
                    return;
                }
                String ref = new ObjectMapper().readTree(ex.getRequestBody().readAllBytes()).path("clientRef").asText();
                boolean fresh = !DEMAT.containsKey(ref);
                String bo = DEMAT.computeIfAbsent(ref, k -> "12081600" + String.format("%08d", DEMAT.size() + 1));
                byte[] body = ("{\"boId\":\"" + bo + "\"}").getBytes();
                ex.sendResponseHeaders(fresh ? 201 : 200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.createContext("/member/v1/clients/", ex -> {
                String code = ex.getRequestURI().getPath().substring("/member/v1/clients/".length());
                REGISTERED.put(code, new ObjectMapper().readTree(ex.getRequestBody().readAllBytes()).path("boId").asText());
                byte[] body = "{}".getBytes();
                ex.sendResponseHeaders(201, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
