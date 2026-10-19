// Guards an issued checkout immediately with a ref so repeated clicks cannot race the asynchronous
// React state update.
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
        // The ref changes synchronously; a second click can arrive before React applies pending state.
        lock.current = true;
        setPending(true);
        try {
            await onCheckout();
        } finally {
            // Release on both success and failure so the same retained checkout intent can be retried.
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
