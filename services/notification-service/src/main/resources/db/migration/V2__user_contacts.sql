-- Local copy of user -> e-mail, filled from auth-service's UserRegistered
-- events, so notification-service never reads auth_db (ADR 0002).
CREATE TABLE user_contacts (
    user_id    BIGINT PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    full_name  VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
