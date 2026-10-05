package app.sprout.accounts.domain;

import app.sprout.accounts.config.AccountsProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Opens Sprout accounts after a simulated KYC: the customer is old enough, has an individual's PAN
 * that no other account uses, and a Sprout Bank UPI address that exists.
 */
@Service
public class Accounts {

    public record Account(UUID id, UUID userId, String legalName, String panMasked, String bankVpa, String status, String dematAccount,
                          Instant openedAt) {}

    /** An individual's PAN: three letters, P (person), a letter, four digits, a letter. */
    private static final Pattern PAN = Pattern.compile("^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$");
    private static final Pattern ANY_PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient db;
    private final Clock clock;
    private final AccountsProperties props;
    private final Custody custody;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Onward onward;

    public Accounts(JdbcClient db, Clock clock, AccountsProperties props, Custody custody, Onward onward) {
        this.onward = onward;
        this.db = db;
        this.clock = clock;
        this.props = props;
        this.custody = custody;
    }

    public Account open(UUID userId, String legalName, LocalDate birthDate, String panInput, String vpaInput) {
        String name = legalName == null ? "" : legalName.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give your full legal name (2 to 100 characters).");
        }
        if (find(userId).isPresent()) {
            throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "You already have a Sprout account.");
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (birthDate == null || birthDate.isAfter(today) || birthDate.isBefore(LocalDate.of(1900, 1, 1))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give a real date of birth.");
        }
        if (birthDate.plusYears(props.minimumAge()).isAfter(today)) {
            throw kyc("You need to be " + props.minimumAge() + " or older to open a Sprout account.");
        }
        String pan = panInput == null ? "" : panInput.trim().toUpperCase(Locale.ROOT);
        if (!ANY_PAN.matcher(pan).matches()) {
            throw kyc("That isn't a valid PAN. It looks like ABCPE1234F.");
        }
        if (!PAN.matcher(pan).matches()) {
            throw kyc("That PAN belongs to a business or organisation; Sprout accounts are for individuals.");
        }
        String vpa = vpaInput == null ? "" : vpaInput.trim().toLowerCase(Locale.ROOT);
        verifyVpa(vpa);
        if (db.sql("SELECT 1 FROM accounts WHERE pan_hash = ?").param(panHash(pan)).query(Integer.class).optional().isPresent()) {
            throw new ApiException(ErrorCode.PAN_IN_USE, "This PAN already has a Sprout account.");
        }
        // last, once everything else is known to be fine: the demat account is real, outside Sprout
        String boId = custody.dematFor(userId, name);
        UUID id = UUID.randomUUID();
        try {
            db.sql("INSERT INTO accounts (id, user_id, legal_name, birth_date, pan_hash, pan_masked, bank_vpa, status, bo_id, opened_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)")
                    .params(id, userId, name, Date.valueOf(birthDate), panHash(pan), "XXXXX" + pan.substring(5), vpa, boId,
                            Timestamp.from(clock.instant()))
                    .update();
        } catch (DuplicateKeyException e) {
            if (find(userId).isPresent()) {
                throw new ApiException(ErrorCode.ACCOUNT_EXISTS, "You already have a Sprout account.");
            }
            throw new ApiException(ErrorCode.PAN_IN_USE, "This PAN already has a Sprout account.");
        }
        return find(userId).orElseThrow();
    }

    private static ApiException kyc(String reason) {
        return new ApiException(ErrorCode.KYC_REJECTED, reason);
    }

    /** Asks Sprout Bank whether the UPI address exists. */
    private void verifyVpa(String vpa) {
        if (!vpa.matches("^[a-z0-9.\\-_]{2,64}@[a-z]{2,32}$")) {
            throw new ApiException(ErrorCode.VPA_NOT_FOUND, "That isn't a UPI address. It looks like name@sproutbank.");
        }
        HttpRequest req = onward.headers(HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/vpas/"
                        + URLEncoder.encode(vpa, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(3)).header("X-Partner-Key", props.bank().partnerKey()).GET()).build();
        int status;
        try {
            status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "We can't reach Sprout Bank right now. Nothing was saved; try again shortly.",
                    5, java.util.Map.of());
        }
        if (status == 404) {
            throw new ApiException(ErrorCode.VPA_NOT_FOUND, "No Sprout Bank account has the UPI address " + vpa + ".");
        }
        if (status != 200) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Sprout Bank couldn't check that UPI address. Try again shortly.",
                    5, java.util.Map.of());
        }
    }

    public Optional<Account> find(UUID userId) {
        return db.sql("SELECT id, user_id, legal_name, pan_masked, bank_vpa, status, bo_id, opened_at FROM accounts WHERE user_id = ?")
                .param(userId).query(Accounts::account).optional();
    }

    /** The account, with a demat account opened for it first if it was opened before demat accounts existed. */
    public Optional<Account> ensureDemat(UUID userId) {
        Optional<Account> a = find(userId);
        if (a.isPresent() && a.get().dematAccount() == null) {
            String boId = custody.dematFor(userId, a.get().legalName());
            db.sql("UPDATE accounts SET bo_id = ? WHERE user_id = ? AND bo_id IS NULL").params(boId, userId).update();
            return find(userId);
        }
        return a;
    }

    private String panHash(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.panPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(pan.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Account account(ResultSet rs, int n) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("legal_name"),
                rs.getString("pan_masked"), rs.getString("bank_vpa"), rs.getString("status"), rs.getString("bo_id"),
                rs.getTimestamp("opened_at").toInstant());
    }
}
