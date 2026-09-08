CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    amount NUMERIC(12,2) NOT NULL
);
