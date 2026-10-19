// Retains the key for an unchanged checkout intent across ambiguous network failures, with a
// memory fallback if storage is blocked.
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
        // A request key belongs to the full customer/item intent; changing contents must create a
        // different key.
        if (previous?.intent === intent && typeof previous.key === "string")
            return previous.key;
    } catch {
        /* A blocked storage policy still permits the in-memory retry guard. */
    }
    // Blocked sessionStorage still permits safe retries within this page lifetime.
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
