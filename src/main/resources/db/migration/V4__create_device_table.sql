CREATE TABLE devices (
    device_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    device_type VARCHAR(64) NOT NULL,
    location VARCHAR(200),
    active BOOLEAN NOT NULL DEFAULT TRUE
);
