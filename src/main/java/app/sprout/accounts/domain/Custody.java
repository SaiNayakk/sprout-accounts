package app.sprout.accounts.domain;

import app.sprout.accounts.config.AccountsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Where a customer's shares will live: a demat account at the depository (Sprout opens it as their
 * depository participant), registered with the clearing corporation under the customer's client code
 * (their user id, the code Sprout puts on their orders), so trades settle into it.
 *
 * <p>Both calls are idempotent per customer, so a retried onboarding finds what the first attempt made.
 */
@Component
public class Custody {

    static final Duration DEADLINE = Duration.ofSeconds(3);

    private final AccountsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public Custody(AccountsProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    /** Opens (or finds) the customer's demat account and registers it for settlement. Returns its 16-digit id. */
    public String dematFor(UUID userId, String holderName) {
        JsonNode opened = call(HttpRequest.newBuilder(URI.create(props.depository().url() + "/participant/v1/accounts"))
                .header("Content-Type", "application/json").header("X-Participant-Key", props.depository().participantKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(Map.of("clientRef", userId.toString(), "holderName", holderName)))),
                "the depository");
        String boId = opened.path("boId").asText();
        call(HttpRequest.newBuilder(URI.create(props.clearing().url() + "/member/v1/clients/" + userId))
                .header("Content-Type", "application/json").header("X-Member-Key", props.clearing().memberKey())
                .PUT(HttpRequest.BodyPublishers.ofString(write(Map.of("boId", boId)))), "the clearing corporation");
        return boId;
    }

    private JsonNode call(HttpRequest.Builder req, String who) {
        try {
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException(who + " answered " + res.statusCode());
            }
            return json.readTree(res.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "We couldn't open your demat account with " + who
                    + " just now. Nothing was saved; try again shortly.", 5, Map.of());
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
