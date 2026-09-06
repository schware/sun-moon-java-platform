CREATE TABLE orders (
    id NUMBER(19) PRIMARY KEY,
    customer_id VARCHAR2(64) NOT NULL,
    amount NUMBER(12,2) NOT NULL
);
