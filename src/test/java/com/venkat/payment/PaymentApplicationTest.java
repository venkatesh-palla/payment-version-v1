package com.venkat.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaymentApplicationTest {

    @Test
    void applicationClassLoads() {
        Assertions.assertNotNull(PaymentApplication.class);
    }
}

