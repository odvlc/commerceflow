package com.commerceflow.payment;

import com.commerceflow.contracts.CheckoutEvents.AuthorizePayment;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

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
        return payments.authorize(orderId, request, correlation);
    }

    @PostMapping("/internal/v1/payments/{orderId}/cancel")
    public PaymentService.PaymentView cancel(
            @PathVariable UUID orderId, @RequestAttribute("correlationId") String correlation) {
        return payments.cancel(orderId, correlation);
    }
}
