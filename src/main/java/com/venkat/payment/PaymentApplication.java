package com.venkat.payment;

import com.venkat.payment.config.PaymentProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Main Spring Boot entrypoint for the QR-Based UPI Payment Service.
 */
@SpringBootApplication
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentApplication {

    public static void main(final String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    /**
     * Injects standard UTC Clock bean across application components.
     * Can be overridden with fixed clock in unit and integration test fixtures.
     *
     * @return UTC system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

