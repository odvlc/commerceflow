// Set a ref before React updates state so rapid clicks cannot issue two checkouts.
import {useRef, useState} from "react";

export function CheckoutAction({
                                   disabled,
                                   onCheckout,
                               }: {
    disabled: boolean;
    onCheckout: () => Promise<void>;
}) {
    const lock = useRef(false);
    const [pending, setPending] = useState(false);

    async function submit() {
        if (lock.current || disabled) return;
        // A second click can arrive before React applies the pending state update.
        lock.current = true;
        setPending(true);
        try {
            await onCheckout();
        } finally {
            // Let the user retry the same checkout intent after either outcome.
            lock.current = false;
            setPending(false);
        }
    }

    return (
        <button
            className="primary checkout"
            disabled={disabled || pending}
            onClick={() => void submit()}
        >
            {pending ? "Placing your order…" : "Place order"}{" "}
            <span aria-hidden="true">↗</span>
        </button>
    );
}
