// Installs DOM assertion matchers for unit tests only; it is not part of the production entry
// point.
// Install matchers such as toBeDisabled in the test environment, without importing them into
// production.
import "@testing-library/jest-dom/vitest";
