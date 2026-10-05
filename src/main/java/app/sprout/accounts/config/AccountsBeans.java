package app.sprout.accounts.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
