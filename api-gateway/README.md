# API gateway

## Table of contents

- [Purpose](#purpose)
- [Use](#use)
- [Place in CommerceFlow](#place-in-commerceflow)

## Purpose

The API Gateway is the browser-facing entry point for CommerceFlow APIs. It routes incoming requests to the backend
applications and applies shared web and correlation behavior.

## Use

Start the service stack with `bash scripts/compose-up.sh`. To run it through Maven, start the backend applications it
routes to, then use `./mvnw -pl api-gateway -am spring-boot:run`.

The default port is `8080`, configured in `src/main/resources/application.yaml`. Route destinations are configurable for
local or container networking. Check that file for the current route paths and environment variable names.

## Place in CommerceFlow

The React frontend sends API requests through the gateway. The gateway has no business database; each destination
service remains responsible for authentication and authorization checks on its own data and operations.
