-- MockPrep schema (PostgreSQL). All SQL hand-written; no ORM.
-- Runs on every startup but only creates what is missing, so bookings survive restarts.
-- To start over: docker compose down -v (deletes the database volume).

-- Every account. Role decides which endpoints the account may use.
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,            -- BCrypt hash, never plaintext
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Milestone 4 adds the ADMIN role (runs the autonomous agent). Replace the old inline CHECK,
-- which PostgreSQL named users_role_check, so this also upgrades an existing database.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'PROVIDER', 'ADMIN'));

-- Provider profile (a mentor / interviewer). 1:1 with a PROVIDER user.
CREATE TABLE IF NOT EXISTS providers (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    headline     VARCHAR(200) NOT NULL,              -- e.g. "Senior SWE, 8 yrs backend"
    bio          TEXT
);

-- Bookable service types (resume review, mock interviews, ...).
CREATE TABLE IF NOT EXISTS services (
    id               BIGSERIAL    PRIMARY KEY,
    name             VARCHAR(100) NOT NULL UNIQUE,
    description      TEXT,
    duration_minutes INT          NOT NULL CHECK (duration_minutes > 0),
    price_cents      INT          NOT NULL CHECK (price_cents >= 0)
);

-- An open time window a provider offers for one service.
CREATE TABLE IF NOT EXISTS availability_slots (
    id          BIGSERIAL   PRIMARY KEY,
    provider_id BIGINT      NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    service_id  BIGINT      NOT NULL REFERENCES services (id),
    start_time  TIMESTAMP   NOT NULL,
    end_time    TIMESTAMP   NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'BOOKED', 'REMOVED')),
    version     INT         NOT NULL DEFAULT 0,      -- optimistic-lock counter, +1 on every status change
    CONSTRAINT ck_slot_time_order CHECK (end_time > start_time),
    -- Target for the composite FK below (lets appointments prove slot/service agree).
    CONSTRAINT uq_slot_id_service UNIQUE (id, service_id)
);

-- Upgrade from Milestone 1, where this was a plain UNIQUE constraint (no-op on a new database).
ALTER TABLE availability_slots DROP CONSTRAINT IF EXISTS uq_slot_provider_start;
-- A provider cannot publish two live slots that start at the same moment.
-- REMOVED slots are excluded so a provider can re-add a time they removed.
CREATE UNIQUE INDEX IF NOT EXISTS uq_slot_provider_start ON availability_slots (provider_id, start_time)
    WHERE status <> 'REMOVED';

CREATE INDEX IF NOT EXISTS ix_slots_open_start ON availability_slots (status, start_time);

-- A booking: links a customer, a slot and the slot's service.
CREATE TABLE IF NOT EXISTS appointments (
    id           BIGSERIAL   PRIMARY KEY,
    slot_id      BIGINT      NOT NULL,
    service_id   BIGINT      NOT NULL,
    customer_id  BIGINT      NOT NULL REFERENCES users (id),
    status       VARCHAR(20) NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED')),
    notes        TEXT,                                -- e.g. target role, resume link
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP,
    -- The appointment's service must be the slot's service.
    CONSTRAINT fk_appt_slot_service FOREIGN KEY (slot_id, service_id)
        REFERENCES availability_slots (id, service_id)
);

-- DOUBLE-BOOKING GUARD: at most one *active* (BOOKED) appointment per slot.
-- Cancelled rows stay for history and don't block rebooking the slot.
CREATE UNIQUE INDEX IF NOT EXISTS uq_appt_active_slot ON appointments (slot_id) WHERE status = 'BOOKED';

CREATE INDEX IF NOT EXISTS ix_appt_customer ON appointments (customer_id);

-- Milestone 4: set when the autonomous reminder agent has queued this appointment's reminder,
-- so each appointment gets at most one reminder.
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP;

-- TRANSACTIONAL OUTBOX (fixes two Milestone 3 bugs: lost and out-of-order notifications).
-- A booking/cancel inserts its notification row here IN THE SAME TRANSACTION as the booking,
-- so the notification exists exactly when the booking exists, even if the app crashes right
-- after the commit. NotificationRelay then sends rows in id order (per appointment), marking
-- each SENT, rescheduling it (PENDING + next_attempt_at) or giving up (FAILED).
CREATE TABLE IF NOT EXISTS notification_outbox (
    id              BIGSERIAL    PRIMARY KEY,         -- also the delivery order
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,     -- e.g. appointment-12-BOOKING_CONFIRMED
    appointment_id  BIGINT       NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
    recipient       VARCHAR(255) NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    message         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,  -- delivery attempts so far
    next_attempt_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_outbox_status_id ON notification_outbox (status, id);

-- Milestone 3's after-the-fact fallback table, replaced by the outbox above.
DROP TABLE IF EXISTS pending_notifications;

-- Milestone 4 (bonus: persistent conversation memory). Spring AI's JdbcChatMemoryRepository
-- reads and writes this table, so a chat with the booking agent survives an app restart.
-- Same layout as Spring AI's schema-postgresql.sql, with a longer conversation_id
-- ("<user id>:<uuid>", so one user can never load another user's conversation).
CREATE TABLE IF NOT EXISTS spring_ai_chat_memory (
    conversation_id VARCHAR(100) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");
