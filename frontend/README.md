# Frontend

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The frontend is a React and TypeScript storefront. It handles Keycloak login, product browsing and search, cart and
checkout interactions, and the optional AI copilot interface.

## Use

Install dependencies with `npm ci` in this directory. Start the development server with `npm run dev`; Vite binds to
`127.0.0.1:5173`. The API base and Keycloak settings are configured in the Vite and authentication source files. For the
full local workflow, use the root [`README.md`](../README.md), which starts the required services and infrastructure.

Useful commands from this directory are `npm run build`, `npm run lint`, `npm test`, and `npm run test:e2e`. Browser
tests require the local browser stack described in the root guide.

## Place in CommerceFlow

The browser talks to backend APIs through the API Gateway. Keycloak handles sign-in with the browser's PKCE flow;
backend services validate tokens and enforce resource permissions. The frontend is built and served separately from the
Java Maven modules.
