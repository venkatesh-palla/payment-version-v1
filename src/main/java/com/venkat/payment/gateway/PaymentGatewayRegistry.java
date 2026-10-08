package com.venkat.payment.gateway;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Registry holding all configured PaymentGateway implementations.
 */
@Component
public class PaymentGatewayRegistry {

    private final Map<String, PaymentGateway> gateways = new ConcurrentHashMap<>();

    public PaymentGatewayRegistry(final List<PaymentGateway> gatewayList) {
        for (final PaymentGateway gateway : gatewayList) {
            this.gateways.put(gateway.gatewayName().toLowerCase(), gateway);
        }
    }

    /**
     * Looks up a gateway implementation by name.
     *
     * @param gatewayName name identifier
     * @return gateway instance
     * @throws IllegalArgumentException if gateway not registered
     */
    public PaymentGateway getGateway(final String gatewayName) {
        if (gatewayName == null) {
            throw new IllegalArgumentException("Gateway name must not be null");
        }
        final PaymentGateway gateway = this.gateways.get(gatewayName.toLowerCase());
        if (gateway == null) {
            throw new IllegalArgumentException("Unsupported payment gateway: " + gatewayName);
        }
        return gateway;
    }
}

