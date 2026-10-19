/*
 * Exposes service payment operations and inspection of synthetic outcomes, without integrating a
 * real payment provider.
 */
package com.commerceflow.payment;

import com.commerceflow.contracts.CheckoutEvents.AuthorizePayment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/api/v1/admin/payments/{orderId}")
    public PaymentService.PaymentView get(@PathVariable UUID orderId) {
        return payments.get(orderId);
    }

    @PostMapping("/internal/v1/payments/{orderId}/authorize")
    public PaymentService.PaymentView authorize(
            @PathVariable UUID orderId,
            @RequestBody AuthorizePayment request,
            @RequestAttribute("correlationId") String correlation) {
        // The service persists the order-keyed outcome and request fingerprint before exposing a
        // successful result.
        return payments.authorize(orderId, request, correlation);
    }

    @PostMapping("/internal/v1/payments/{orderId}/cancel")
    public PaymentService.PaymentView cancel(
            @PathVariable UUID orderId, @RequestAttribute("correlationId") String correlation) {
        return payments.cancel(orderId, correlation);
    }
}
