package com.venkat.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.venkat.payment.api.CreatePaymentRequest;
import com.venkat.payment.api.CreatePaymentResponse;
import com.venkat.payment.api.RequestInProgressException;
import com.venkat.payment.config.PaymentProperties;
import com.venkat.payment.domain.Payment;
import com.venkat.payment.domain.PaymentStatus;
import com.venkat.payment.gateway.PaymentGateway;
import com.venkat.payment.gateway.PaymentGatewayRegistry;
import com.venkat.payment.gateway.model.PaymentCreationResponse;
import com.venkat.payment.repository.IdempotencyKeyRepository;
import com.venkat.payment.repository.IdempotencyKeyRepository.IdempotencyRecord;
import com.venkat.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentConcurrencyTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayRegistry gatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentStatusUpdateService statusUpdateService;

    private PaymentProperties paymentProperties;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private PaymentService paymentService;

    // In-memory atomic map simulating DB unique constraint on (scope, idempotency_key)
    private final Map<String, IdempotencyRecord> idempotencyStore = new ConcurrentHashMap<>();
    private final AtomicInteger gatewayInvocationCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        this.paymentProperties = new PaymentProperties();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

        final IdempotencyKeyRepository inMemoryIdempotencyRepo = new IdempotencyKeyRepository(null) {
            @Override
            public boolean tryClaimKey(final String scope, final String idempotencyKey, final String requestHash, final Instant expiresAt) {
                final String storeKey = scope + ":" + idempotencyKey;
                final IdempotencyRecord newRecord = new IdempotencyRecord(
                        scope, idempotencyKey, requestHash, "IN_PROGRESS", null, null, null, Instant.now(), expiresAt
                );
                return idempotencyStore.putIfAbsent(storeKey, newRecord) == null;
            }

            @Override
            public Optional<IdempotencyRecord> findByKey(final String scope, final String idempotencyKey) {
                return Optional.ofNullable(idempotencyStore.get(scope + ":" + idempotencyKey));
            }

            @Override
            public void completeKey(final String scope, final String idempotencyKey, final int statusCode, final String responseBody, final UUID paymentId) {
                final String storeKey = scope + ":" + idempotencyKey;
                final IdempotencyRecord existing = idempotencyStore.get(storeKey);
                if (existing != null) {
                    idempotencyStore.put(storeKey, new IdempotencyRecord(
                            scope, idempotencyKey, existing.requestHash(), "COMPLETED", statusCode, responseBody, paymentId, existing.createdAt(), existing.expiresAt()
                    ));
                }
            }
        };

        this.paymentService = new PaymentService(
                this.paymentRepository,
                inMemoryIdempotencyRepo,
                this.gatewayRegistry,
                new PaymentStateMachine(),
                this.statusUpdateService,
                this.paymentProperties,
                this.objectMapper,
                this.fixedClock
        );
    }

    @Test
    @DisplayName("20 concurrent requests with SAME Idempotency-Key -> exactly 1 payment created, all return same paymentId, no 500s")
    void twentyConcurrentRequestsWithSameKeyYieldsSinglePayment() throws Exception {
        final int concurrency = 20;
        final String idempotencyKey = "CONCURRENT-KEY-12345678";
        final CreatePaymentRequest request = new CreatePaymentRequest("ORD-CONCUR-1", new BigDecimal("100.00"), "INR", "CUST-1");
        final UUID generatedPaymentId = UUID.randomUUID();

        when(this.gatewayRegistry.getGateway("fake")).thenReturn(this.paymentGateway);
        when(this.paymentRepository.insert(any(Payment.class))).thenAnswer(invocation -> {
            final Payment p = invocation.getArgument(0);
            return new Payment(
                    generatedPaymentId, p.getPaymentReference(), p.getOrderId(), p.getCustomerId(),
                    p.getAmount(), p.getCurrency(), p.getGateway(), p.getGatewayOrderId(),
                    p.getGatewayPaymentId(), p.getStatus(), p.getPaymentMethod(), p.getQrData(),
                    p.getExpiresAt(), p.getPaidAt(), p.isRequiresManualReview(), p.getReviewReason(),
                    p.getVerificationAttempts(), p.getNextVerificationAt(), p.getVersion(),
                    p.getCreatedAt(), p.getUpdatedAt()
            );
        });
        when(this.paymentGateway.createPayment(any())).thenAnswer(invocation -> {
            gatewayInvocationCount.incrementAndGet();
            Thread.sleep(50); // Simulate brief gateway latency
            return new PaymentCreationResponse("fake_gw_ord_1", "upi://pay?fake=1", fixedClock.instant().plusSeconds(900));
        });
        when(this.paymentRepository.updateGatewayDetailsAndStatus(any(), any(), any(), any(), any(Long.class)))
                .thenReturn(1);

        final ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        final CountDownLatch startGun = new CountDownLatch(1);
        final List<Future<CreatePaymentResponse>> futures = new ArrayList<>();
        final List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startGun.await();
                while (true) {
                    try {
                        return paymentService.createPayment(idempotencyKey, request);
                    } catch (final RequestInProgressException ripe) {
                        // Concurrent caller polls/retries as expected (HTTP 409 with Retry-After)
                        Thread.sleep(15);
                    } catch (final Exception ex) {
                        errors.add(ex);
                        throw ex;
                    }
                }
            }));
        }

        // Fire all threads simultaneously
        startGun.countDown();

        final List<CreatePaymentResponse> responses = new ArrayList<>();
        for (final Future<CreatePaymentResponse> future : futures) {
            responses.add(future.get());
        }

        executor.shutdown();

        // Verification assertions
        assertThat(errors).isEmpty();
        assertThat(responses).hasSize(concurrency);
        assertThat(gatewayInvocationCount.get()).isEqualTo(1); // Gateway called EXACTLY ONCE

        final UUID canonicalPaymentId = responses.get(0).paymentId();
        assertThat(canonicalPaymentId).isEqualTo(generatedPaymentId);

        // Every single concurrent thread received the exact same payment_id
        for (final CreatePaymentResponse res : responses) {
            assertThat(res.paymentId()).isEqualTo(canonicalPaymentId);
            assertThat(res.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(res.qrCode()).isEqualTo("upi://pay?fake=1");
        }
    }
}
