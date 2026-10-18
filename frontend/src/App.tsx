import type {FormEvent} from "react";
import {useEffect, useRef, useState} from "react";
import {identity, login, logout, roles} from "./auth";
import type {Cart, Notice, Order, Page, Product, ProductSummary, ProductWrite, Scenario, Stock,} from "./api";
import {ApiError, money, mutation, request, statusLabel, terminal,} from "./api";
import {CheckoutAction} from "./CheckoutAction";
import {checkoutKey} from "./checkout";

type Run = (action: () => Promise<void>) => Promise<void>;
const emptyCart: Cart = {items: [], version: 0, ttlSeconds: 0};
const errorMessage = (error: unknown) =>
    error instanceof Error
        ? error.message
        : "Something went wrong. Please try again.";

function Mark() {
    return (
        <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
            <path
                d="M9 10V8a7 7 0 0 1 14 0v2M5 10h22l2 20H3L5 10Z"
                stroke="currentColor"
                strokeWidth="2"
            />
            <path d="m10 20 4 4 8-9" stroke="currentColor" strokeWidth="2"/>
        </svg>
    );
}

export default function App({initialError = ""}: { initialError?: string }) {
    const [tab, setTab] = useState("shop");
    const [cart, setCart] = useState<Cart>(emptyCart);
    const [products, setProducts] = useState<Record<string, ProductSummary>>({});
    const [busy, setBusy] = useState(false),
        working = useRef(false);
    const [error, setError] = useState(initialError),
        [message, setMessage] = useState("");
    const [scenario, setScenario] = useState<Scenario>("APPROVE");
    const authenticated = !!identity.authenticated,
        admin = roles().includes("ADMIN"),
        support = roles().includes("SUPPORT");
    const canPurchase = admin || roles().includes("CUSTOMER");
    const run: Run = async (action) => {
        if (working.current) return;
        working.current = true;
        setBusy(true);
        setError("");
        setMessage("");
        try {
            await action();
        } catch (failure) {
            setError(errorMessage(failure));
        } finally {
            working.current = false;
            setBusy(false);
        }
    };
    useEffect(() => {
        if (authenticated && canPurchase)
            request<Cart>("/cart")
                .then(setCart)
                .catch((e) => setError(errorMessage(e)));
    }, [authenticated, canPurchase]);

    function remember(items: ProductSummary[]) {
        setProducts((previous) => ({
            ...previous,
            ...Object.fromEntries(items.map((p) => [p.id, p])),
        }));
    }

    async function add(product: ProductSummary) {
        if (!authenticated) {
            await login();
            return;
        }
        if (!canPurchase) {
            setError("A customer account is required to place orders.");
            return;
        }
        await run(async () => {
            setCart(
                await mutation<Cart>("/cart/items", "POST", {
                    productId: product.id,
                    quantity: 1,
                }),
            );
            remember([product]);
            setMessage(`${product.name} added to your cart.`);
        });
    }

    async function checkout() {
        await run(async () => {
            const items = [...cart.items].sort((a, b) =>
                a.productId.localeCompare(b.productId),
            );
            const intent = JSON.stringify({
                customer: identity.subject,
                items,
                scenario,
            });
            const key = checkoutKey(intent);
            const order = await mutation<Order>(
                "/orders",
                "POST",
                {items, paymentScenario: scenario},
                key,
            );
            setTab("orders");
            setMessage(
                `Order ${order.id.slice(0, 8)} received. Its status will update automatically.`,
            );
            try {
                for (const item of items)
                    await mutation(
                        `/cart/items/${item.productId}`,
                        "DELETE",
                        undefined,
                        `${key}-clear-${item.productId}`,
                    );
                setCart(await request<Cart>("/cart"));
            } catch {
                setError(
                    "Your order was received. The cart could not refresh; retrying this checkout will reuse your original order.",
                );
            }
        });
    }

    return (
        <>
            <div className="announcement">
                A little more considered. Every day.{" "}
                <span>No real payments · Local demonstration</span>
            </div>
            <header className="site-header">
                <a className="brand" href="/" aria-label="CommerceFlow home">
                    <Mark/>
                    Commerce<span>Flow</span>
                    <i>®</i>
                </a>
                <nav aria-label="Main navigation">
                    <button
                        aria-current={tab === "shop" ? "page" : undefined}
                        onClick={() => setTab("shop")}
                    >
                        Collection
                    </button>
                    {authenticated && canPurchase && (
                        <button
                            aria-current={tab === "orders" ? "page" : undefined}
                            onClick={() => setTab("orders")}
                        >
                            My orders
                        </button>
                    )}
                    {(admin || support) && (
                        <button
                            aria-current={tab === "admin" ? "page" : undefined}
                            onClick={() => setTab("admin")}
                        >
                            Administration
                        </button>
                    )}
                </nav>
                <div className="account">
                    {authenticated ? (
                        <>
              <span>
                {identity.tokenParsed?.preferred_username || "Customer"}
              </span>
                            <button onClick={() => void logout()}>Sign out</button>
                        </>
                    ) : (
                        <button className="outline" onClick={() => void login()}>
                            Sign in ↗
                        </button>
                    )}
                </div>
            </header>
            <main>
                {error && (
                    <div role="alert" className="banner error">
                        {error}
                        <button aria-label="Dismiss error" onClick={() => setError("")}>
                            ×
                        </button>
                    </div>
                )}
                {message && (
                    <div role="status" className="banner success">
                        {message}
                    </div>
                )}
                {tab === "shop" && (
                    <section className="hero">
                        <div>
                            <p className="eyebrow">THE EVERYDAY COLLECTION / 01</p>
                            <h1>
                                Good things.
                                <br/>
                                <em>Seamless days.</em>
                            </h1>
                            <p>
                                Discover thoughtful essentials for work, home, and everything in
                                between.
                            </p>
                            <a className="text-link" href="#collection">
                                Explore the collection <span>↓</span>
                            </a>
                        </div>
                        <div className="hero-art" aria-hidden="true">
                            <div className="art-circle"/>
                            <div className="art-package">
                                <Mark/>
                                <span>
                  GOOD THINGS
                  <br/>
                  INSIDE.
                </span>
                            </div>
                            <span className="art-tag">CURATED WITH CARE</span>
                        </div>
                    </section>
                )}
                <div
                    className={
                        tab === "admin" || (authenticated && !canPurchase)
                            ? "wide"
                            : "workspace"
                    }
                >
                    <div>
                        {tab === "shop" && (
                            <Products onAdd={add} remember={remember} busy={busy} run={run}/>
                        )}
                        {tab === "orders" && <OrderHistory run={run} busy={busy}/>}
                        {tab === "admin" && (
                            <Admin run={run} busy={busy} canManage={admin}/>
                        )}
                    </div>
                    {tab !== "admin" && (!authenticated || canPurchase) && (
                        <aside className="cart-panel" aria-label="Shopping cart">
                            <div className="section-heading">
                                <h2>Your cart</h2>
                                <span className="count">{cart.items.length}</span>
                            </div>
                            {!authenticated ? (
                                <div className="empty">
                                    <Mark/>
                                    <p>Your next favorite is waiting.</p>
                                    <button className="text-link" onClick={() => void login()}>
                                        Sign in to start a cart ↗
                                    </button>
                                </div>
                            ) : cart.items.length === 0 ? (
                                <div className="empty">
                                    <Mark/>
                                    <p>A little room for good things.</p>
                                    <span>Your cart is empty.</span>
                                </div>
                            ) : (
                                <>
                                    <ul className="cart-items">
                                        {cart.items.map((item) => (
                                            <li key={item.productId}>
                                                <div>
                                                    <strong>
                                                        {products[item.productId]?.name ||
                                                            `Product ${item.productId.slice(0, 8)}`}
                                                    </strong>
                                                    <small>
                                                        {products[item.productId] &&
                                                            money(
                                                                products[item.productId].price,
                                                                products[item.productId].currency,
                                                            )}
                                                    </small>
                                                </div>
                                                <label>
                                                    Qty
                                                    <input
                                                        aria-label={`Quantity for ${products[item.productId]?.name || item.productId}`}
                                                        key={`${item.productId}:${item.quantity}`}
                                                        type="number"
                                                        min="1"
                                                        max="1000"
                                                        defaultValue={item.quantity}
                                                        disabled={busy}
                                                        onBlur={(e) => {
                                                            const value = e.currentTarget.valueAsNumber;
                                                            if (
                                                                e.currentTarget.checkValidity() &&
                                                                value !== item.quantity
                                                            )
                                                                void run(async () =>
                                                                    setCart(
                                                                        await mutation<Cart>(
                                                                            `/cart/items/${item.productId}`,
                                                                            "PATCH",
                                                                            {quantity: value},
                                                                        ),
                                                                    ),
                                                                );
                                                        }}
                                                    />
                                                </label>
                                                <button
                                                    className="remove"
                                                    aria-label={`Remove ${products[item.productId]?.name || item.productId}`}
                                                    disabled={busy}
                                                    onClick={() =>
                                                        void run(async () =>
                                                            setCart(
                                                                await mutation<Cart>(
                                                                    `/cart/items/${item.productId}`,
                                                                    "DELETE",
                                                                ),
                                                            ),
                                                        )
                                                    }
                                                >
                                                    ×
                                                </button>
                                            </li>
                                        ))}
                                    </ul>
                                    <p className="muted">
                                        Current prices and availability are confirmed at checkout.
                                    </p>
                                    <label className="field">
                                        Simulated payment
                                        <select
                                            value={scenario}
                                            onChange={(e) => setScenario(e.target.value as Scenario)}
                                            disabled={busy}
                                        >
                                            <option value="APPROVE">Approved</option>
                                            <option value="DECLINE">Declined</option>
                                            <option value="TIMEOUT">Timeout</option>
                                            <option value="DELAY">Delayed</option>
                                        </select>
                                    </label>
                                    <CheckoutAction
                                        disabled={busy || !cart.items.length}
                                        onCheckout={checkout}
                                    />
                                    <p className="secure-note">
                                        Simulated checkout. No card details required.
                                    </p>
                                </>
                            )}
                        </aside>
                    )}
                </div>
            </main>
            <footer>
                <a className="brand" href="/">
                    Commerce<span>Flow</span>
                </a>
                <p>Everyday essentials. Thoughtfully connected.</p>
                <span>Local demo · No real charges</span>
            </footer>
        </>
    );
}

function Products({
                      onAdd,
                      remember,
                      busy,
                      run,
                  }: {
    onAdd: (p: ProductSummary) => Promise<void>;
    remember: (p: ProductSummary[]) => void;
    busy: boolean;
    run: Run;
}) {
    const [items, setItems] = useState<ProductSummary[]>([]),
        [total, setTotal] = useState(0),
        [page, setPage] = useState(0);
    const [query, setQuery] = useState(""),
        [loading, setLoading] = useState(true),
        [error, setError] = useState("");
    const [selected, setSelected] = useState<Product>();
    const rememberRef = useRef(remember);
    rememberRef.current = remember;
    useEffect(() => {
        const abort = new AbortController();
        setLoading(true);
        setError("");
        const path = query
            ? `/products/search?${query}&page=${page}&size=9`
            : `/products?page=${page}&size=9`;
        request<Page<ProductSummary>>(path, {signal: abort.signal})
            .then((result) => {
                setItems(result.items);
                setTotal(result.totalElements);
                rememberRef.current(result.items);
            })
            .catch((e) => {
                if (!abort.signal.aborted) setError(errorMessage(e));
            })
            .finally(() => {
                if (!abort.signal.aborted) setLoading(false);
            });
        return () => abort.abort();
    }, [query, page]);

    function search(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        const data = new FormData(event.currentTarget),
            parameters = new URLSearchParams();
        for (const [key, value] of data)
            if (String(value).trim()) parameters.set(key, String(value).trim());
        setPage(0);
        setQuery(parameters.toString());
    }

    return (
        <section id="collection">
            <div className="section-heading">
                <div>
                    <p className="eyebrow">MADE FOR YOUR EVERYDAY</p>
                    <h2>
                        The collection<span className="total">{total} products</span>
                    </h2>
                </div>
            </div>
            <form className="search-form" onSubmit={search}>
                <label className="search-input">
                    <span aria-hidden="true">⌕</span>
                    <input
                        name="q"
                        aria-label="Search products"
                        placeholder="Find your next favorite…"
                        maxLength={200}
                    />
                </label>
                <button className="primary" type="submit">
                    Search
                </button>
                <div className="filters">
                    <label>
                        Category
                        <input
                            name="category"
                            placeholder="All categories"
                            maxLength={80}
                        />
                    </label>
                    <label>
                        Min price
                        <input
                            name="minPrice"
                            type="number"
                            min="0"
                            step="0.01"
                            placeholder="0.00"
                        />
                    </label>
                    <label>
                        Max price
                        <input
                            name="maxPrice"
                            type="number"
                            min="0"
                            step="0.01"
                            placeholder="Any"
                        />
                    </label>
                    <label>
                        Currency
                        <select name="currency" defaultValue="USD">
                            <option>USD</option>
                            <option>EUR</option>
                            <option>GBP</option>
                        </select>
                    </label>
                    <label>
                        Sort
                        <select name="sort" defaultValue="relevance">
                            <option value="relevance">Most relevant</option>
                            <option value="priceAsc">Price: low to high</option>
                            <option value="priceDesc">Price: high to low</option>
                            <option value="newest">Recently updated</option>
                        </select>
                    </label>
                </div>
            </form>
            {error && (
                <p role="alert" className="banner error">
                    {error}
                </p>
            )}
            {loading ? (
                <p role="status" className="loading">
                    Finding good things…
                </p>
            ) : items.length === 0 ? (
                <p className="empty">No products found. Try a different search.</p>
            ) : (
                <div className="product-grid">
                    {items.map((p, index) => (
                        <article className="product-card" key={p.id}>
                            <button
                                className={`product-art tone-${index % 4}`}
                                aria-label={`View ${p.name}`}
                                onClick={() =>
                                    void run(async () =>
                                        setSelected(await request<Product>(`/products/${p.id}`)),
                                    )
                                }
                            >
                                <Mark/>
                                <span>{p.category}</span>
                                <i aria-hidden="true">↗</i>
                            </button>
                            <div className="product-info">
                                <small>{p.category}</small>
                                <h3>{p.name}</h3>
                                <p>{p.description}</p>
                                <div className="product-bottom">
                                    <strong>{money(p.price, p.currency)}</strong>
                                    <button
                                        className="add"
                                        aria-label={`Add ${p.name} to cart`}
                                        disabled={busy}
                                        onClick={() => void onAdd(p)}
                                    >
                                        +
                                    </button>
                                </div>
                            </div>
                        </article>
                    ))}
                </div>
            )}
            <Pagination page={page} size={9} total={total} onChange={setPage}/>
            {selected && (
                <section className="details" aria-label="Product details">
                    <button
                        className="remove"
                        aria-label="Close product details"
                        onClick={() => setSelected(undefined)}
                    >
                        ×
                    </button>
                    <p className="eyebrow">
                        {selected.category} / {selected.sku}
                    </p>
                    <h2>{selected.name}</h2>
                    <p>{selected.description}</p>
                    <strong>{money(selected.price, selected.currency)}</strong>
                    <button
                        className="primary"
                        disabled={busy}
                        onClick={() => void onAdd(selected)}
                    >
                        Add to cart
                    </button>
                </section>
            )}
        </section>
    );
}

function Pagination({
                        page,
                        size,
                        total,
                        onChange,
                    }: {
    page: number;
    size: number;
    total: number;
    onChange: (n: number) => void;
}) {
    return (
        <div className="pagination">
            <button disabled={page === 0} onClick={() => onChange(page - 1)}>
                ← Previous
            </button>
            <span>
        Page {page + 1} of {Math.max(1, Math.ceil(total / size))}
      </span>
            <button
                disabled={(page + 1) * size >= total}
                onClick={() => onChange(page + 1)}
            >
                Next →
            </button>
        </div>
    );
}

function OrderHistory({
                          run,
                          busy,
                          administrative = false,
                      }: {
    run: Run;
    busy: boolean;
    administrative?: boolean;
}) {
    const [orders, setOrders] = useState<Order[]>([]),
        [notices, setNotices] = useState<Notice[]>([]),
        [total, setTotal] = useState(0),
        [page, setPage] = useState(0),
        [error, setError] = useState("");
    const [revision, setRevision] = useState(0),
        [loading, setLoading] = useState(true);
    useEffect(() => {
        let alive = true;

        async function load() {
            try {
                const result = await request<Page<Order>>(
                    `${administrative ? "/admin" : ""}/orders?page=${page}&size=20`,
                );
                if (alive) {
                    setOrders(result.items);
                    setTotal(result.totalElements);
                    setError("");
                }
                if (!administrative) {
                    const updates = await request<Page<Notice>>("/notifications?size=5");
                    if (alive) setNotices(updates.items);
                }
            } catch (e) {
                if (alive) setError(errorMessage(e));
            } finally {
                if (alive) setLoading(false);
            }
        }

        void load();
        const timer = setInterval(() => void load(), 2500);
        return () => {
            alive = false;
            clearInterval(timer);
        };
    }, [page, administrative, revision]);
    return (
        <section>
            <p className="eyebrow">
                {administrative ? "OPERATIONS" : "FOLLOW EVERY STEP"}
            </p>
            <h1 className="page-title">
                {administrative ? "Order monitoring" : "Your orders"}
            </h1>
            <p className="muted">Statuses update automatically.</p>
            {error && (
                <p role="alert" className="banner error">
                    {error}
                </p>
            )}
            {loading ? (
                <p role="status">Loading orders…</p>
            ) : orders.length === 0 ? (
                <div className="empty">
                    Your first good thing is still ahead. Explore the collection to get
                    started.
                </div>
            ) : (
                <div className="table-wrap">
                    <table>
                        <thead>
                        <tr>
                            <th>Order</th>
                            <th>Placed</th>
                            <th>Total</th>
                            <th>Status</th>
                            <th>
                                <span className="sr-only">Actions</span>
                            </th>
                        </tr>
                        </thead>
                        <tbody>
                        {orders.map((order) => (
                            <tr key={order.id}>
                                <td>
                                    <strong>#{order.id.slice(0, 8)}</strong>
                                    <small>
                                        {order.items.reduce(
                                            (sum, item) => sum + item.quantity,
                                            0,
                                        )}{" "}
                                        items
                                    </small>
                                </td>
                                <td>{new Date(order.createdAt).toLocaleString()}</td>
                                <td>{money(order.totalAmount, order.currency)}</td>
                                <td>
                    <span className={`status ${order.status.toLowerCase()}`}>
                      {statusLabel(order.status)}
                    </span>
                                </td>
                                <td>
                                    {!administrative &&
                                        !terminal(order.status) &&
                                        order.status !== "COMMITTING_INVENTORY" && (
                                            <button
                                                disabled={
                                                    busy || order.status.startsWith("CANCELLING")
                                                }
                                                onClick={() =>
                                                    void run(async () => {
                                                        await mutation(
                                                            `/orders/${order.id}/cancel`,
                                                            "POST",
                                                        );
                                                        setRevision((r) => r + 1);
                                                    })
                                                }
                                            >
                                                Cancel
                                            </button>
                                        )}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}
            <Pagination page={page} size={20} total={total} onChange={setPage}/>
            {notices.length > 0 && (
                <section className="updates">
                    <h2>Latest updates</h2>
                    {notices.map((n) => (
                        <p key={n.id}>
                            <span className="update-dot"/>
                            {n.message}
                            <small>#{n.orderId.slice(0, 8)}</small>
                        </p>
                    ))}
                </section>
            )}
        </section>
    );
}

const blankProduct: ProductWrite = {
    sku: "",
    name: "",
    description: "",
    category: "",
    price: "",
    currency: "USD",
    active: true,
};

function Admin({
                   run,
                   busy,
                   canManage,
               }: {
    run: Run;
    busy: boolean;
    canManage: boolean;
}) {
    const [items, setItems] = useState<Product[]>([]),
        [editing, setEditing] = useState<Product>(),
        [form, setForm] = useState<ProductWrite>(blankProduct);
    const stockSelection = useRef("");
    const [stockId, setStockId] = useState(""),
        [quantity, setQuantity] = useState(""),
        [stock, setStock] = useState<Stock>();
    const [error, setError] = useState(""),
        [revision, setRevision] = useState(0);
    useEffect(() => {
        if (canManage)
            request<Page<Product>>("/admin/products?size=100")
                .then((p) => setItems(p.items))
                .catch((e) => setError(errorMessage(e)));
    }, [revision, canManage]);

    function edit(product: Product) {
        setEditing(product);
        setForm({...product});
    }

    async function save(event: FormEvent) {
        event.preventDefault();
        await run(async () => {
            if (editing)
                await request(`/admin/products/${editing.id}`, {
                    method: "PUT",
                    headers: {"If-Match": `"${editing.version}"`},
                    body: JSON.stringify(form),
                });
            else await mutation("/admin/products", "POST", form);
            setEditing(undefined);
            setForm(blankProduct);
            setRevision((r) => r + 1);
        });
    }

    async function loadStock(id: string) {
        stockSelection.current = id;
        setStockId(id);
        setStock(undefined);
        setQuantity("");
        if (!id) return;
        try {
            const result = await request<Stock>(`/admin/inventory/${id}`);
            if (stockSelection.current === id) {
                setStock(result);
                setQuantity(String(result.availableQuantity));
            }
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 404))
                setError(errorMessage(e));
        }
    }

    return (
        <div className="admin-grid">
            <div className="admin-orders">
                <OrderHistory administrative run={run} busy={busy}/>
            </div>
            {error && (
                <p role="alert" className="banner error">
                    {error}
                </p>
            )}
            {canManage && (
                <>
                    <section className="panel">
                        <p className="eyebrow">CATALOG</p>
                        <h2>{editing ? "Edit product" : "Add a product"}</h2>
                        <form className="admin-form" onSubmit={(event) => void save(event)}>
                            {(["sku", "name", "category"] as const).map((field) => (
                                <label className="field" key={field}>
                                    {field === "sku"
                                        ? "SKU"
                                        : field.charAt(0).toUpperCase() + field.slice(1)}
                                    <input
                                        required
                                        maxLength={
                                            field === "sku" ? 64 : field === "category" ? 80 : 200
                                        }
                                        pattern={field === "sku" ? "[A-Za-z0-9_\\-]+" : undefined}
                                        value={form[field]}
                                        onChange={(e) =>
                                            setForm({...form, [field]: e.target.value})
                                        }
                                    />
                                </label>
                            ))}
                            <label className="field">
                                Description
                                <textarea
                                    required
                                    maxLength={4000}
                                    value={form.description}
                                    onChange={(e) =>
                                        setForm({...form, description: e.target.value})
                                    }
                                />
                            </label>
                            <div className="two-columns">
                                <label className="field">
                                    Price
                                    <input
                                        required
                                        type="number"
                                        min="0"
                                        max="999999999.99"
                                        step="0.01"
                                        value={form.price}
                                        onChange={(e) =>
                                            setForm({...form, price: e.target.value})
                                        }
                                    />
                                </label>
                                <label className="field">
                                    Currency
                                    <select
                                        value={form.currency}
                                        onChange={(e) =>
                                            setForm({...form, currency: e.target.value})
                                        }
                                    >
                                        <option>USD</option>
                                        <option>EUR</option>
                                        <option>GBP</option>
                                    </select>
                                </label>
                            </div>
                            <label className="checkbox">
                                <input
                                    type="checkbox"
                                    checked={form.active}
                                    onChange={(e) =>
                                        setForm({...form, active: e.target.checked})
                                    }
                                />
                                Visible in the collection
                            </label>
                            <button className="primary" disabled={busy}>
                                {editing ? "Save changes" : "Create product"}
                            </button>
                            {editing && (
                                <button
                                    type="button"
                                    onClick={() => {
                                        setEditing(undefined);
                                        setForm(blankProduct);
                                    }}
                                >
                                    Cancel editing
                                </button>
                            )}
                        </form>
                        <ul className="admin-products">
                            {items.map((p) => (
                                <li key={p.id}>
                                    <div>
                                        <strong>{p.name}</strong>
                                        <small>
                                            {p.sku} · {p.active ? "Active" : "Inactive"}
                                        </small>
                                    </div>
                                    <button onClick={() => edit(p)}>Edit</button>
                                </li>
                            ))}
                        </ul>
                    </section>
                    <section className="panel">
                        <p className="eyebrow">INVENTORY</p>
                        <h2>Manage stock</h2>
                        <label className="field">
                            Product
                            <select
                                aria-label="Product"
                                value={stockId}
                                onChange={(e) => void loadStock(e.target.value)}
                            >
                                <option value="">Select a product</option>
                                {items.map((p) => (
                                    <option key={p.id} value={p.id}>
                                        {p.name}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <form
                            className="admin-form"
                            onSubmit={(event) => {
                                event.preventDefault();
                                void run(async () => {
                                    const updated = await request<Stock>(
                                        `/admin/inventory/${stockId}`,
                                        {
                                            method: "PUT",
                                            body: JSON.stringify({
                                                availableQuantity: Number(quantity),
                                                ...(stock ? {version: stock.version} : {}),
                                            }),
                                        },
                                    );
                                    setStock(updated);
                                });
                            }}
                        >
                            <label className="field">
                                Available units
                                <input
                                    type="number"
                                    required
                                    min="0"
                                    max="1000000000"
                                    value={quantity}
                                    onChange={(e) => setQuantity(e.target.value)}
                                />
                            </label>
                            {stock && (
                                <p className="muted">
                                    {stock.reservedQuantity} units are reserved for active orders.
                                </p>
                            )}
                            <button
                                className="primary"
                                disabled={busy || !stockId || quantity === ""}
                            >
                                Update stock
                            </button>
                        </form>
                    </section>
                </>
            )}
        </div>
    );
}
