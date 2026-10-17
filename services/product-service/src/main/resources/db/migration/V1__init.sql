CREATE TABLE categories (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT REFERENCES categories (id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    price       BIGINT       NOT NULL CHECK (price >= 0),
    image_url   VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_active_created ON products (active, created_at DESC);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    sent_at      TIMESTAMPTZ
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Seed data for local development
INSERT INTO categories (name, slug) VALUES
    ('Electronics', 'electronics'),
    ('Books', 'books'),
    ('Clothing', 'clothing');

INSERT INTO products (category_id, name, description, price, image_url) VALUES
    (1, 'Wireless Mouse', 'A simple wireless mouse', 250000, NULL),
    (1, 'Mechanical Keyboard', '87-key mechanical keyboard', 1500000, NULL),
    (2, 'Clean Code', 'A book about writing better code', 450000, NULL),
    (3, 'Plain T-Shirt', '100% cotton t-shirt', 120000, NULL);
