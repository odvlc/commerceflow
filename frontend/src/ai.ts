import {accessToken} from "./auth";
import {ApiError, mutation, request} from "./api";

export type Mode = "CUSTOMER" | "SUPPORT";

export interface Capabilities {
    enabled: boolean;
    modelReady: boolean;
    chatModel: string;
    roles: string[];
    actionsEnabled: boolean;
    maximumMessageCharacters: number;
}

export interface Conversation {
    id: string;
    mode: Mode;
    updatedAt: string;
}

export interface Evidence {
    id: string;
    sourceId: string;
    version: number;
    title: string;
    heading: string;
    chunkId: string;
    content: string;
}

export interface ChatMessage {
    id: string;
    runId: string;
    role: "USER" | "ASSISTANT";
    content: string;
    citations: Evidence[];
    sequence: number;
}

export interface Proposal {
    id: string;
    runId: string;
    operation: "ADD" | "SET" | "DELETE";
    productId: string;
    quantity: number;
    argumentsHash: string;
    cartRevision: string;
    state: string;
    version: number;
    expiresAt: string;
    preview: {
        productName: string;
        unitPrice: string;
        currency: string;
        beforeQuantity: number;
        afterQuantity: number;
    };
}

export interface Run {
    id: string;
    conversationId: string;
    state: string;
    updatedAt: string;
    errorCode?: string;
    rounds: number;
    steps: { id: string; kind: string; name: string; state: string }[];
    proposals: Proposal[];
}

export interface Execution {
    id: string;
    state: string;
    errorCode?: string;
    result?: unknown;
}

export interface StreamEvent {
    id: number;
    type: string;
    payload: unknown;
}

export const settled = (state: string) =>
    ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state);
export const paused = (state: string) =>
    ["WAITING_CONFIRMATION", "AUTHENTICATION_REQUIRED"].includes(state);
export const createConversation = (mode: Mode, key: string) =>
    mutation<Conversation>("/ai/conversations", "POST", {mode}, key);
export const sendMessage = (
    conversation: string,
    content: string,
    key: string,
) =>
    mutation<Run>(
        `/ai/conversations/${conversation}/messages`,
        "POST",
        {content},
        key,
    );

/** Header-authenticated SSE with incremental UTF-8 parsing and bounded frame sizes. */
export async function streamRun(
    run: string,
    after: number,
    signal: AbortSignal,
    onEvent: (event: StreamEvent) => void,
): Promise<void> {
    const token = await accessToken();
    if (!token) throw new ApiError(401, "Sign in again to reconnect.");
    // fetch supports bearer headers and a replay cursor without placing credentials in the URL.
    const response = await fetch(
        (import.meta.env.VITE_API_BASE_URL || "/api/v1") + `/ai/runs/${run}/events`,
        {
            headers: {
                Authorization: `Bearer ${token}`,
                Accept: "text/event-stream",
                "Last-Event-ID": String(after),
                "X-Correlation-ID": crypto.randomUUID(),
            },
            signal,
            credentials: "omit",
            cache: "no-store",
        },
    );
    if (!response.ok)
        throw new ApiError(
            response.status,
            "Copilot connection unavailable. Reconnect to read the saved result.",
        );
    if (!response.body)
        throw new Error("Streaming is unavailable in this browser.");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
        while (true) {
            const {value, done} = await reader.read();
            // Network chunks can split UTF-8 characters and CRLF pairs; retain incomplete frames.
            buffer = (buffer + decoder.decode(value, {stream: !done})).replace(
                /\r\n/g,
                "\n",
            );
            if (buffer.length > 128000)
                throw new Error("Copilot event exceeds the supported size.");
            let boundary: number;
            while ((boundary = buffer.indexOf("\n\n")) >= 0) {
                const frame = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                let id = after,
                    type = "message";
                const data: string[] = [];
                for (const line of frame.split("\n")) {
                    if (line.startsWith("id:")) id = Number(line.slice(3).trim());
                    if (line.startsWith("event:")) type = line.slice(6).trim();
                    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
                }
                if (data.length && Number.isSafeInteger(id) && id > after) {
                    // Advance only after delivery so reconnect can replay events the consumer did not accept.
                    onEvent({id, type, payload: JSON.parse(data.join("\n"))});
                    after = id;
                }
            }
            if (done) break;
        }
    } finally {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
    }
}

export const getRun = (id: string) => request<Run>(`/ai/runs/${id}`);
