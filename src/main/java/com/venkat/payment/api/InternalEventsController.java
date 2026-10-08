package com.venkat.payment.api;

import com.venkat.payment.repository.PaymentOutboxRepository.OutboxRecord;
import com.venkat.payment.service.InternalEventsService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API endpoint for event pulling and operational outbox maintenance.
 * Controllers only validate, delegate, and map.
 */
@RestController
@RequestMapping("/api/v1/internal/payment-events")
public class InternalEventsController {

    private final InternalEventsService internalEventsService;

    public InternalEventsController(final InternalEventsService internalEventsService) {
        this.internalEventsService = internalEventsService;
    }

    @GetMapping
    public ResponseEntity<List<OutboxRecord>> pullEvents(
            @RequestParam(name = "after", defaultValue = "0") final long afterSequenceNo,
            @RequestParam(name = "limit", defaultValue = "100") final int limit) {
        final int sanitizedLimit = Math.min(Math.max(limit, 1), 500);
        final List<OutboxRecord> events = this.internalEventsService.getEventsAfter(afterSequenceNo, sanitizedLimit);
        return ResponseEntity.ok(events);
    }

    @PostMapping("/requeue-failed")
    public ResponseEntity<Map<String, Object>> requeueFailedEvents() {
        final int count = this.internalEventsService.requeueFailedEvents();
        return ResponseEntity.ok(Map.of(
                "status", "REQUEUED",
                "requeuedCount", count
        ));
    }
}

