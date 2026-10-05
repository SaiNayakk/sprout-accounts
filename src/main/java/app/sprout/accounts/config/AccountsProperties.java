package app.sprout.accounts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.accounts} in accounts.yml. */
@ConfigurationProperties("sprout.accounts")
public record AccountsProperties(int minimumAge, String panPepper, String serviceKey, Bank bank, Depository depository,
                                 Clearing clearing) {

    public record Bank(String url, String partnerKey) {}

    public record Depository(String url, String participantKey) {}

    public record Clearing(String url, String memberKey) {}
}
