-- One PostgreSQL container locally, but one DATABASE per service,
-- so no service can read another service's tables (ADR-0002).
CREATE DATABASE auth_db;
CREATE DATABASE product_db;
CREATE DATABASE inventory_db;
CREATE DATABASE order_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
