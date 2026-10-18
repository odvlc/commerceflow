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
        lock.current = true;
        setPending(true);
        try {
            await onCheckout();
        } finally {
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
