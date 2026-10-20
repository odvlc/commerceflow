# CommerceFlow simulated payment guide

<!-- TOC START -->

## Table of contents

- [Payment scenarios](#payment-scenarios)
- [Current order explanations](#current-order-explanations)

<!-- TOC END -->

## Payment scenarios

- APPROVE authorizes a simulated payment.
- DECLINE cancels the order after reserved inventory is released.
- TIMEOUT reconciles uncertain payment before releasing inventory.
- DELAY produces a delayed result.

The demo uses no card details or real funds.

## Current order explanations

Use the authenticated Order explanation API for the customer's actual order state and public reason. Never infer a
particular cancellation cause from this guide alone.
