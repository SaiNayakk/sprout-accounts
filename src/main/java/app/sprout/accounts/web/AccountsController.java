package app.sprout.accounts.web;

import app.sprout.accounts.config.AccountsProperties;
import app.sprout.accounts.domain.Accounts;
import app.sprout.accounts.domain.Accounts.Account;
import app.sprout.accounts.domain.ApiException;
import app.sprout.accounts.domain.ErrorCode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The accounts API (accounts-v1.yaml) for the signed-in user, plus /internal for other Sprout
 * services, which must present the service key.
 */
@RestController
public class AccountsController {

    public record OpenRequest(String legalName, LocalDate dateOfBirth, String pan, String bankVpa) {}

    private final Accounts accounts;
    private final AccountsProperties props;

    public AccountsController(Accounts accounts, AccountsProperties props) {
        this.accounts = accounts;
        this.props = props;
    }

    @PostMapping("/v1/accounts")
    public ResponseEntity<Map<String, Object>> open(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                    @RequestBody OpenRequest req) {
        Account a = accounts.open(userId(user), req.legalName(), req.dateOfBirth(), req.pan(), req.bankVpa());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(a));
    }

    @GetMapping("/v1/accounts/me")
    public Map<String, Object> me(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return dto(accounts.find(userId(user))
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.")));
    }

    /** For payments: the account a user's money belongs to, and where it goes. */
    @GetMapping("/internal/v1/accounts/{userId}")
    public Map<String, Object> internal(@RequestHeader(value = "X-Service-Key", required = false) String key,
                                        @PathVariable UUID userId) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(), props.serviceKey().getBytes())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only Sprout services can call this.");
        }
        return dto(accounts.find(userId).orElseThrow(() -> new ApiException(ErrorCode.NO_ACCOUNT, "No account for that user.")));
    }

    private static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
        }
    }

    static Map<String, Object> dto(Account a) {
        return Map.of("id", a.id().toString(), "userId", a.userId().toString(), "legalName", a.legalName(),
                "panMasked", a.panMasked(), "bankVpa", a.bankVpa(), "status", a.status(), "openedAt", a.openedAt().toString());
    }
}
