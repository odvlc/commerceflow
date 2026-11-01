// Reuses a checkout key after ambiguous network failures. Falls back to memory when storage is blocked.
let fallback: { intent: string; key: string } | undefined;

/** Reuse the key after an ambiguous network failure, including a page reload where storage is available. */
export function checkoutKey(
    intent: string,
    storage: Storage = sessionStorage,
): string {
    try {
        const previous = JSON.parse(
            storage.getItem("commerceflow.checkout") || "null",
        );
        // A key identifies the customer and item list. Changed contents need a new key.
        if (previous?.intent === intent && typeof previous.key === "string")
            return previous.key;
    } catch {
        /* Use the in-memory retry guard when storage is blocked. */
    }
    // Keep retries safe for this page lifetime when sessionStorage is blocked.
    if (fallback?.intent === intent) return fallback.key;
    const key = crypto.randomUUID();
    fallback = {intent, key};
    try {
        storage.setItem("commerceflow.checkout", JSON.stringify(fallback));
    } catch {
        /* Keep the key for this page lifetime. */
    }
    return key;
}
