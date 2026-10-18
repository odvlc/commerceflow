# CommerceFlow simulated payment guide

## Payment scenarios

APPROVE authorizes a simulated payment. DECLINE cancels the order after reserved inventory is released. TIMEOUT
reconciles uncertain payment before releasing inventory. DELAY produces a delayed result. No card details or real funds
are used.

## Current order explanations

Use the authenticated Order explanation API for the customer's actual order state and public reason. Never infer a
particular cancellation cause from this guide alone.
