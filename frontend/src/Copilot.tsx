import type {FormEvent} from "react";
import {useEffect, useRef, useState} from "react";
import {identity} from "./auth";
import type {Page} from "./api";
import {money, mutation, request} from "./api";
import type {Capabilities, ChatMessage, Conversation, Evidence, Execution, Mode, Proposal, Run,} from "./ai";
import {createConversation, getRun, paused, sendMessage, settled, streamRun,} from "./ai";

export function Copilot({
                            onCartChanged,
                        }: {
    onCartChanged: () => Promise<void>;
}) {
    const [open, setOpen] = useState(false);
    if (!identity.authenticated) return null;
    return (
        <aside className="copilot-shell" aria-label="CommerceFlow copilot">
            <button
                className="copilot-toggle"
                aria-expanded={open}
                aria-controls="copilot-panel"
                onClick={() => setOpen(!open)}
            >
                Local copilot {open ? "−" : "+"}
            </button>
            {open && <Panel key={identity.subject} onCartChanged={onCartChanged}/>}
        </aside>
    );
}

function Panel({onCartChanged}: { onCartChanged: () => Promise<void> }) {
    const [cap, setCap] = useState<Capabilities>();
    const [mode, setMode] = useState<Mode>("CUSTOMER");
    const [conversations, setConversations] = useState<Conversation[]>([]);
    const [conversation, setConversation] = useState<string>();
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [run, setRun] = useState<Run>();
    const [text, setText] = useState("");
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [connected, setConnected] = useState(false);
    const currentRun = useRef<string | undefined>(undefined);
    const currentConversation = useRef<string | undefined>(undefined);
    const [historyPage, setHistoryPage] = useState(0);
    const [historyTotal, setHistoryTotal] = useState(0);
    const controller = useRef<AbortController | undefined>(undefined);
    const cursor = useRef(0);
    const lock = useRef(false);
    // Retain the exact intent/key after a lost HTTP response; retry must not create a second run.
    const pending = useRef<
        { key: string; text: string; conversation?: string; mode: Mode } | undefined
    >(undefined);
    const alive = useRef(true);
    const fail = (e: unknown) => {
        if (alive.current)
            setError(e instanceof Error ? e.message : "Copilot is unavailable.");
    };
    useEffect(() => {
        alive.current = true;
        request<Capabilities>("/ai/capabilities")
            .then((value) => {
                if (!alive.current) return;
                setCap(value);
                if (!value.roles.some((r) => r === "CUSTOMER" || r === "ADMIN"))
                    setMode("SUPPORT");
            })
            .catch(fail);
        request<Page<Conversation>>("/ai/conversations?size=20")
            .then((page) => {
                if (alive.current) setConversations(page.items);
            })
            .catch(() => undefined);
        return () => {
            alive.current = false;
            controller.current?.abort();
            pending.current = undefined;
        };
    }, []);
    // Cancelling the run cannot retract an issued Cart request; a late acknowledgment is still audited.
    const uncertainCancelled =
        run?.state === "CANCELLED" &&
        run.proposals.some((p) =>
            ["CONFIRMED", "OUTCOME_UNKNOWN"].includes(p.state),
        );
    useEffect(() => {
        if (!uncertainCancelled || !run) return;
        let attempts = 0;
        const id = run.id;
        const timer = setInterval(() => {
            if (++attempts > 15) {
                clearInterval(timer);
                return;
            }
            void refresh(id).catch(fail);
        }, 2000);
        return () => clearInterval(timer);
    }, [run?.id, uncertainCancelled]);

    async function loadMessages(id: string, requestedPage?: number) {
        let page = await request<Page<ChatMessage>>(
            `/ai/conversations/${id}/messages?size=100&page=${requestedPage ?? 0}`,
        );
        const target =
            requestedPage ?? Math.max(0, Math.ceil(page.totalElements / 100) - 1);
        if (target !== page.page)
            page = await request<Page<ChatMessage>>(
                `/ai/conversations/${id}/messages?size=100&page=${target}`,
            );
        if (alive.current && currentConversation.current === id) {
            setMessages(page.items);
            setHistoryPage(target);
            setHistoryTotal(page.totalElements);
        }
        return page.items;
    }

    async function refresh(id: string) {
        const value = await getRun(id);
        if (!alive.current || currentRun.current !== id) return value;
        setRun((previous) =>
            previous?.id === id &&
            Date.parse(previous.updatedAt) > Date.parse(value.updatedAt)
                ? previous
                : value,
        );
        await loadMessages(value.conversationId);
        if (value.proposals.some((p) => p.state === "SUCCEEDED"))
            await onCartChanged();
        return value;
    }

    async function connect(id: string) {
        controller.current?.abort();
        const next = new AbortController();
        controller.current = next;
        setConnected(true);
        try {
            await streamRun(id, cursor.current, next.signal, (event) => {
                cursor.current = event.id;
                void refresh(id).catch(fail);
            });
            await refresh(id);
        } catch (e) {
            if (!next.signal.aborted) fail(e);
        } finally {
            if (controller.current === next && alive.current) setConnected(false);
        }
    }

    async function command(action: () => Promise<void>) {
        if (lock.current) return;
        lock.current = true;
        setBusy(true);
        setError("");
        try {
            await action();
        } catch (e) {
            fail(e);
        } finally {
            lock.current = false;
            if (alive.current) setBusy(false);
        }
    }

    async function submit(e: FormEvent) {
        e.preventDefault();
        if (!text.trim() || (run && !settled(run.state))) return;
        await command(async () => {
            if (!pending.current)
                pending.current = {
                    key: crypto.randomUUID(),
                    text: text.trim(),
                    conversation,
                    mode,
                };
            const intent = pending.current;
            if (!intent.conversation)
                intent.conversation = (
                    await createConversation(intent.mode, `conversation-${intent.key}`)
                ).id;
            const value = await sendMessage(
                intent.conversation,
                intent.text,
                intent.key,
            );
            if (!alive.current) return;
            pending.current = undefined;
            currentConversation.current = intent.conversation;
            currentRun.current = value.id;
            setConversation(intent.conversation);
            setConversations((list) =>
                list.some((c) => c.id === intent.conversation)
                    ? list
                    : [
                        {
                            id: intent.conversation!,
                            mode: intent.mode,
                            updatedAt: new Date().toISOString(),
                        },
                        ...list,
                    ],
            );
            setRun(value);
            setText("");
            cursor.current = 0;
            await loadMessages(intent.conversation);
            void connect(value.id);
        });
    }

    async function select(id: string) {
        await command(async () => {
            controller.current?.abort();
            cursor.current = 0;
            setRun(undefined);
            setMessages([]);
            currentConversation.current = id;
            currentRun.current = undefined;
            setConversation(id);
            const history = await loadMessages(id);
            const last = history.at(-1);
            if (last) {
                currentRun.current = last.runId;
                const value = await refresh(last.runId);
                if (!settled(value.state) && !paused(value.state))
                    void connect(value.id);
            }
        });
    }

    function reset(next: Mode) {
        currentRun.current = undefined;
        currentConversation.current = undefined;
        controller.current?.abort();
        pending.current = undefined;
        cursor.current = 0;
        setMode(next);
        setConversation(undefined);
        setRun(undefined);
        setMessages([]);
        setError("");
        setText("");
    }

    async function decide(p: Proposal, confirm: boolean) {
        await command(async () => {
            if (confirm) {
                await mutation<Execution>(`/ai/proposals/${p.id}/confirm`, "POST", {
                    version: p.version,
                    argumentsHash: p.argumentsHash,
                });
                await refresh(p.runId);
                void connect(p.runId);
            } else {
                await mutation(`/ai/proposals/${p.id}/reject`, "POST", {
                    version: p.version,
                });
                await refresh(p.runId);
            }
        });
    }

    const active = !!run && !settled(run.state);
    const customer = cap?.roles.some((r) => r === "CUSTOMER" || r === "ADMIN");
    const support = cap?.roles.some((r) => r === "SUPPORT" || r === "ADMIN");
    return (
        <section id="copilot-panel" className="copilot-panel">
            <h2>CommerceFlow copilot</h2>
            <p className="muted">
                Local answers from verified store data and synthetic demo policies.
            </p>
            {error && (
                <p role="alert" className="banner error">
                    {error}
                </p>
            )}
            {!cap ? (
                <p role="status">
                    Copilot is unavailable or still connecting. You can continue shopping.
                </p>
            ) : (
                <>
                    <p className="muted">
                        {cap.modelReady
                            ? "Local model ready"
                            : "Local model unavailable — prepare models or retry after warmup."}
                    </p>
                    <label className="field">
                        Mode
                        <select
                            value={mode}
                            disabled={busy || active}
                            onChange={(e) => reset(e.target.value as Mode)}
                        >
                            {customer && <option value="CUSTOMER">My shopping</option>}
                            {support && (
                                <option value="SUPPORT">Support investigation</option>
                            )}
                        </select>
                    </label>
                    <label className="field">
                        Conversation
                        <select
                            value={conversation || ""}
                            disabled={busy || active}
                            onChange={(e) =>
                                e.target.value ? void select(e.target.value) : reset(mode)
                            }
                        >
                            <option value="">New conversation</option>
                            {conversations
                                .filter((c) => c.mode === mode)
                                .map((c) => (
                                    <option key={c.id} value={c.id}>
                                        {new Date(c.updatedAt).toLocaleString()} ·{" "}
                                        {c.id.slice(0, 8)}
                                    </option>
                                ))}
                        </select>
                    </label>
                    <ol className="copilot-messages" aria-label="Conversation messages">
                        {messages.map((m) => (
                            <li key={m.id} className={m.role.toLowerCase()}>
                                <strong>{m.role === "USER" ? "You" : "Copilot"}</strong>
                                <p className="copilot-answer">{m.content}</p>
                                {m.citations.map((c) => (
                                    <Citation key={c.id} evidence={c}/>
                                ))}
                            </li>
                        ))}
                    </ol>
                    {conversation && historyTotal > 100 && (
                        <div className="copilot-controls">
                            <button
                                disabled={busy || historyPage === 0}
                                onClick={() =>
                                    void loadMessages(conversation, historyPage - 1).catch(fail)
                                }
                            >
                                Earlier messages
                            </button>
                            <span>Page {historyPage + 1}</span>
                            <button
                                disabled={busy || (historyPage + 1) * 100 >= historyTotal}
                                onClick={() =>
                                    void loadMessages(conversation, historyPage + 1).catch(fail)
                                }
                            >
                                Later messages
                            </button>
                        </div>
                    )}
                    {run && (
                        <>
                            <p role="status">
                                {run.state.replaceAll("_", " ")}
                                {connected ? " · connected" : ""}
                            </p>
                            <ul className="copilot-progress" aria-label="Investigation steps">
                                {run.steps.map((s) => (
                                    <li key={s.id}>
                                        {s.kind === "MODEL" ? "Local generation" : s.name} ·{" "}
                                        {s.state.toLowerCase()}
                                    </li>
                                ))}
                            </ul>
                            {run.proposals.map((p) => (
                                <section
                                    className="copilot-proposal"
                                    key={p.id}
                                    aria-label="Proposed cart change"
                                >
                                    <h3>Review cart change</h3>
                                    <p>
                                        <strong>{p.preview.productName}</strong> ·{" "}
                                        {money(p.preview.unitPrice, p.preview.currency)}
                                    </p>
                                    <p>
                                        {p.operation}: quantity {p.preview.beforeQuantity} →{" "}
                                        {p.preview.afterQuantity}
                                    </p>
                                    <p className="muted">
                                        Product {p.productId}
                                        <br/>
                                        Cart revision {p.cartRevision}
                                        <br/>
                                        Expires {new Date(p.expiresAt).toLocaleTimeString()}
                                    </p>
                                    <p role="status">{p.state.replaceAll("_", " ")}</p>
                                    {["CONFIRMED", "OUTCOME_UNKNOWN"].includes(p.state) && (
                                        <p className="muted">
                                            An issued cart change can still complete after
                                            cancellation. Review your current cart and the saved
                                            result.
                                        </p>
                                    )}
                                    {p.state === "OUTCOME_UNKNOWN" && (
                                        <p role="alert">
                                            The result is uncertain. Review your current cart before
                                            making another change.
                                        </p>
                                    )}
                                    {p.state === "PROPOSED" &&
                                        mode === "CUSTOMER" &&
                                        cap.actionsEnabled && (
                                            <div className="copilot-controls">
                                                <button
                                                    className="primary"
                                                    disabled={
                                                        busy ||
                                                        run.state !== "WAITING_CONFIRMATION" ||
                                                        Date.parse(p.expiresAt) <= Date.now()
                                                    }
                                                    onClick={() => void decide(p, true)}
                                                >
                                                    Confirm exact change
                                                </button>
                                                <button
                                                    disabled={busy}
                                                    onClick={() => void decide(p, false)}
                                                >
                                                    Reject
                                                </button>
                                            </div>
                                        )}
                                </section>
                            ))}
                            {run.errorCode && (
                                <p role="alert">
                                    {run.errorCode.replaceAll("_", " ")}. Review the saved state
                                    before retrying.
                                </p>
                            )}
                            <div className="copilot-controls">
                                {active && (
                                    <button
                                        disabled={busy}
                                        onClick={() =>
                                            void command(async () => {
                                                await mutation(`/ai/runs/${run.id}/cancel`, "POST");
                                                await refresh(run.id);
                                                controller.current?.abort();
                                            })
                                        }
                                    >
                                        Cancel investigation
                                    </button>
                                )}
                                {run.state === "AUTHENTICATION_REQUIRED" && (
                                    <button
                                        disabled={busy}
                                        onClick={() =>
                                            void command(async () => {
                                                await mutation(`/ai/runs/${run.id}/resume`, "POST");
                                                await refresh(run.id);
                                                void connect(run.id);
                                            })
                                        }
                                    >
                                        Reauthenticate and resume
                                    </button>
                                )}
                                {!connected && (
                                    <button disabled={busy} onClick={() => void connect(run.id)}>
                                        Reconnect to saved result
                                    </button>
                                )}
                            </div>
                        </>
                    )}
                    <form onSubmit={(e) => void submit(e)}>
                        <label className="field">
                            Ask the copilot
                            <textarea
                                value={text}
                                onChange={(e) => {
                                    if (
                                        pending.current &&
                                        e.target.value !== pending.current.text
                                    ) {
                                        setError(
                                            "Retry the original message before editing it, or open a new conversation.",
                                        );
                                        return;
                                    }
                                    setText(e.target.value);
                                }}
                                required
                                maxLength={cap.maximumMessageCharacters}
                                disabled={busy || active}
                                placeholder="Explain a demo policy, find products, or investigate an order UUID."
                            />
                        </label>
                        <button
                            className="primary"
                            disabled={busy || active || !cap.modelReady || !text.trim()}
                        >
                            {pending.current ? "Retry original message" : "Send message"}
                        </button>
                    </form>
                </>
            )}
        </section>
    );
}

function Citation({evidence}: { evidence: Evidence }) {
    let product:
        | { id?: string; name?: string; price?: string; currency?: string }
        | undefined;
    if (evidence.sourceId.startsWith("product/"))
        try {
            product = JSON.parse(evidence.content);
        } catch {
            /* A bounded excerpt is still readable as source text. */
        }
    return (
        <details className="copilot-citation">
            <summary>
                {evidence.title} · v{evidence.version}
            </summary>
            {product?.name && (
                <p>
                    <strong>{product.name}</strong>
                    {product.price && product.currency
                        ? ` · ${money(product.price, product.currency)}`
                        : ""}
                </p>
            )}
            <p>{evidence.heading}</p>
            <p className="copilot-answer">{evidence.content}</p>
            <small>
                Source {evidence.sourceId} · chunk {evidence.chunkId}
            </small>
        </details>
    );
}
