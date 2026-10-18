import { accessToken } from "./auth";

export type Money = string | number;
export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string;
  category: string;
  price: Money;
  currency: string;
  active: boolean;
  version: number;
  updatedAt: string;
}
export type ProductSummary = Pick<
  Product,
  "id" | "sku" | "name" | "description" | "category" | "price" | "currency"
>;
export type ProductWrite = Pick<
  Product,
  "sku" | "name" | "description" | "category" | "price" | "currency" | "active"
>;
export interface Page<T> {
  items: T[];
  totalElements: number;
  page: number;
  size: number;
}
export interface CartItem {
  productId: string;
  quantity: number;
}
export interface Cart {
  items: CartItem[];
  version: number;
  ttlSeconds: number;
}
export type Scenario = "APPROVE" | "DECLINE" | "TIMEOUT" | "DELAY";
export interface Order {
  id: string;
  customerId: string;
  status: string;
  totalAmount: Money;
  currency: string;
  version: number;
  createdAt: string;
  items: (CartItem & { unitPrice: Money })[];
}
export interface Notice {
  id: string;
  orderId: string;
  status: string;
  message: string;
  createdAt: string;
}
export interface Stock {
  productId: string;
  availableQuantity: number;
  reservedQuantity: number;
  version: number;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: string,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const token = await accessToken();
  const headers = new Headers(options.headers);
  headers.set("Content-Type", "application/json");
  headers.set("X-Correlation-ID", crypto.randomUUID());
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(
    (import.meta.env.VITE_API_BASE_URL || "/api/v1") + path,
    { ...options, headers, credentials: "omit", cache: "no-store" },
  );
  const body = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new ApiError(
      response.status,
      body.detail || `Request failed (${response.status}). Please try again.`,
      body.code,
    );
  return body as T;
}
export function mutation<T>(
  path: string,
  method: string,
  body?: unknown,
  key: string = crypto.randomUUID(),
): Promise<T> {
  return request(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: { "Idempotency-Key": key },
  });
}
export const money = (value: Money, currency: string) =>
  new Intl.NumberFormat(undefined, { style: "currency", currency }).format(
    Number(value),
  );
export const terminal = (status: string) =>
  status === "CONFIRMED" || status === "CANCELLED";
export const statusLabel = (status: string) =>
  ({
    PENDING_INVENTORY: "Checking availability",
    AWAITING_PAYMENT: "Processing payment",
    COMMITTING_INVENTORY: "Finalizing",
    CANCELLING_PAYMENT: "Cancelling payment",
    CANCELLING_INVENTORY: "Releasing stock",
    CONFIRMED: "Confirmed",
    CANCELLED: "Cancelled",
  })[status] || status;
