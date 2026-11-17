# Milestone 4: decisions log

### Where things are
| Requirement | Code |
|---|---|
| Agent 1 chat endpoint (ChatClient + tools) | `controller/AssistantController.chat`, `ai/agent/BookingAgent.chat` |
| Tools wrapping the service layer | `ai/agent/BookingAgentTools` (`@Tool` methods) |
| Writes through the transactional service | `BookingAgentTools.book` → `BookingService.book`; `.cancel` → `AppointmentService.cancel` |
| Confirm-before-write | `proposeBooking` / `proposeCancellation` store a `PendingAction`; `confirmPendingAction` checks `proposedInTurn < turn` |
| Multi-step + alternatives + multi-turn | tool loop in Spring AI; `alternativesFor`; `MessageChatMemoryAdvisor` + JDBC chat memory |
| Agent 2 (scheduled, LLM, outbound action) | `ai/reminder/ReminderAgent` (`@Scheduled` cron, `writeReminder`, outbox insert) |
| Manual trigger | `AdminController.runReminderAgent` (`POST /api/admin/agents/reminders/run`) |
| Knowledge source + vector store + RAG | `knowledge/mockprep-handbook.md`, `ai/knowledge/KnowledgeBase`, `PolicyQaService` (`QuestionAnswerAdvisor`) |
| Required test | `AgentBookingToolsTest.doubleBookingIsBlockedWhenTheAgentBooks`, `twoAgentsConfirmingTheSameSlotAtOnceBookItExactlyOnce` |
| API key handling | `application.yml` (`spring.ai.google.genai.api-key: ${GEMINI_API_KEY:}` only), `RuntimeDefaultsEnvironmentPostProcessor` |
| Bonus: persistent conversation memory | `spring_ai_chat_memory` in `schema.sql`, `BookingAgent.history` |
| M3 finding 1: public notification endpoints | `mocknotify/MockNotifyApiKeyFilter`, `RestNotificationGateway` (`X-Api-Key`) |
| M3 finding 2: lost / out-of-order notifications | `notification_outbox` (`schema.sql`), `NotificationOutbox`, `NotificationRelay` |
| JWT | `security/JwtService`, `JwtUserConverter`, `SecurityConfig`, `AuthController.login` |
| Next.js frontend | `frontend/` (`lib/api.ts`, `lib/auth.tsx`, `app/*/page.tsx`) |

### Decisions
1. **Spring AI 1.1.8 with the Google GenAI starter, API-key mode.** Spring AI 2.x needs Spring Boot 4; 1.1 is the
   line for Boot 3.5. Only `api-key` is set; `project-id`/`location` would switch to Vertex AI.
2. **App starts without a key.** An `EnvironmentPostProcessor` switches the Gemini auto-configuration off when
   `GEMINI_API_KEY` is blank (the embedding connection auto-config otherwise fails startup). AI endpoints answer 503.
   CI and tests therefore never call Gemini; `AiTestConfig` provides a stub chat model and a bag-of-words embedding.
3. **Tools are a new object per request, bound to the user.** No tool takes a user id, so a prompt cannot make the
   agent act for someone else. The REST endpoint is CUSTOMER-only, and the services check ownership again.
4. **Confirm-before-write is a code rule, not only a prompt rule.** The pending action lives on the server (the
   model cannot change what gets booked after the user saw the summary), and it can be confirmed only in a later
   turn than the one that proposed it. The prompt also says so, but the guard holds even if the model ignores it.
5. **Tool results use pre-formatted Pacific-time strings** ("Tue Nov 18, 10:00 AM - 11:00 AM PT") so the model
   does not convert times itself.
6. **Agent 2 claims and enqueues in one transaction** (`reminder_sent_at` conditional update + outbox insert). Each
   appointment gets at most one reminder; a cancelled one is skipped; an LLM failure leaves it for the next run.
   The LLM call happens before the transaction, so no database transaction waits on Gemini.
7. **Agent 2 uses RAG too:** the reminder prompt contains the handbook's preparation section for that service.
8. **SimpleVectorStore, one chunk per `##` section.** The handbook is small (17 sections); one section = one topic
   gives clean "sources". Embedded once at startup in the background, lazily retried on first use if that failed.
9. **Transactional outbox replaces "notify after commit + park on failure".** The M3 design lost a notification if
   the JVM died between commit and send, and used one async thread per event, so a quick cancel could overtake its
   confirmation. Now the row commits with the booking, and one relay sends rows in id order, holding later rows of an
   appointment behind an earlier unsent one. An `AFTER_COMMIT` event triggers the relay immediately; a 5 s poll is
   the safety net.
10. **The mock notification service checks an API key** (constant-time compare) on every endpoint, including reading
    messages and the admin mode switch. It is a separate system, so it does not trust scheduler logins.
11. **JWT instead of the HTTP session.** HS256 with a server secret from the environment; Spring's OAuth2 resource
    server verifies tokens. The user row is reloaded per request so role changes and deletions apply at once.
    Stateless, no cookies, so CSRF is off. Token in `sessionStorage` on the client (trade-off in README limitations).
12. **Next.js forwards `/api/*` to Spring Boot** (rewrites), so the browser sees one origin. CORS is still configured
    for a separately hosted frontend (e.g. Vercel + Render).
13. **Gemini as an unreliable external service.** Spring AI's default retry (10 attempts, 2 s x5 backoff) plus the
    SDK's own retries made one overloaded call take 251 s. Now: our own SDK `Client` bean (20 s timeout, SDK retries
    off), `spring.ai.retry.max-attempts: 2`, then `FallbackChatModel` tries lighter models with a 60 s circuit
    breaker. Default model `gemini-3.5-flash-lite` (measured 5/5 OK in about 1 s; `gemini-3.8-flash` 0/5 that night).
    Next.js proxy timeout raised to 120 s for agent turns.
14. **Review of the M4 code for the same classes of bug as the M3 findings** (exposure, loss/duplication):
    confirm made atomic (double-clicked Confirm), re-proposing the same slot keeps the user's confirmation,
    per-user AI rate limit (quota exhaustion = service disruption), `/actuator/metrics` ADMIN-only.
    Covered by `SecurityExposureTest` and new cases in `AgentBookingToolsTest` / `AiFeaturesIntegrationTest`.
15. **ADMIN role added** (operator account `admin`) to run the agent on demand and view the outbox; the final
    project asks for customer and provider/admin roles.

---

# Milestone 3: decisions log

### Where things are (for your report and video)
| Requirement | Code |
|---|---|
| Mock notification service | `mocknotify/MockNotificationController`, `MockNotificationStore`, `MockNotificationMessage` |
| Gateway + coarse-grained DTO | `notification/NotificationGateway`, `RestNotificationGateway`, `NotificationRequest`, `NotificationReceipt` |
| Timeout | `RestNotificationGateway` constructor (`setConnectTimeout` / `setReadTimeout`), `NotificationProperties` |
| Retry + fallback | `NotificationSender.deliver` / `fallBack` |
| Commit-then-notify | `events.publishEvent(...)` in `SlotBooker.bookOnce` and `AppointmentService.cancel`; `AppointmentNotificationListener.onAppointmentChanged` (`AFTER_COMMIT`, `@Async`) |
| Idempotent retries | `NotificationRequest.keyFor`, `Idempotency-Key` header, `MockNotificationStore.accept` (`computeIfAbsent`), `ON CONFLICT DO NOTHING` in `PendingNotificationRepository.park` |
| Failure scenario + recovery | `pending_notifications` in `schema.sql`, `NotificationRedeliveryJob.redeliver` |
| Health endpoint | `controller/HealthController` |
| Metric | `BookingService.book` (`scheduler.booking` timer), `NotificationSender.count` (`scheduler.notifications`) |
| Logging | `log.info/warn/error` in `BookingService`, `AppointmentService`, `NotificationSender`, `NotificationRedeliveryJob`, `HealthController`, `MockNotificationController` |
| Docker / CI | `Dockerfile` (`HEALTHCHECK`), `docker-compose.yml`, `.github/workflows/ci.yml` |
| Required test | `NotificationDownIntegrationTest`; also `NotificationIntegrationTest`, `NotificationSenderTest` |
| Diagram | `docs/distribution-diagram.mmd` / `.png` |

### Decisions
1. **Event + `@TransactionalEventListener(AFTER_COMMIT)`, not a direct call after `book()` returns.** The event
   is tied to the transaction: it fires only on commit, and a rolled-back attempt (lost race, retried by
   `BookingService`) never notifies. Cancel uses the same path.
2. **`@Async` listener.** With a sync call, a slow service would delay the booking response by up to
   3 × 2 s + backoff. Async: the response returns right after the commit (measured 0.06 s with the mock DOWN).
3. **Hand-written retry loop** (like `BookingService`), no Spring Retry/Resilience4j. No new dependency, and each
   step is easy to show in the video.
4. **Fallback is a database table, not memory.** Parked notifications survive a restart and are visible in
   `/health`. Only failed notifications are written there; successful ones are not stored.
5. **Mock lives in the same app but has its own DTO class** and is reached only over real HTTP (default base URL
   `http://localhost:${port}/mock-notify`), so the timeouts are real. `NOTIFICATION_BASE_URL` points it elsewhere.
6. **`/health` stays UP when only notifications fail** (`DEGRADED`), because bookings still work. It goes 503 only
   when the database is unreachable. Hikari `connection-timeout` lowered to 5 s so that answer comes quickly.
7. **One timer for the booking metric** with an `outcome` tag, not several counters: it gives counts per outcome
   and latency from one meter.

### Known limits
- If the JVM dies after a commit but before the notification is sent or parked, that notification is lost.
  A transactional outbox (insert the row inside the booking transaction) would close this gap. Normal shutdown
  waits up to 15 s for queued notifications.
- `/mock-notify/**` (including the mode switch and the list of sent messages with emails) and
  `/actuator/metrics/**` are open without login, for the demo. In a real deployment, the mock would not be
  deployed (`mock-notify.enabled=false`) and metrics would be restricted.
- Booking and cancel notifications run on separate background threads, so if a customer cancels within
  milliseconds of booking, the cancellation could arrive before the confirmation.
- With several app instances, two redelivery jobs could send the same row; the idempotency key makes that harmless.

### Verified
- `mvn -B verify` with Testcontainers: 79 tests, 0 failures (run several times).
- `docker compose up --build`: container `healthy`; booked with the mock DOWN → 201 in 0.06 s → parked →
  mock UP → redelivered within 30 s, stored once; `/health` DEGRADED → UP; `/actuator/metrics/scheduler.booking` OK.

---

# Milestone 2: decisions log

## Summary (autonomous run, night of 2026-10-07)

### What was built
- **Login + RBAC:** Spring Security session login (`POST /api/auth/login`), BCrypt hashes, role read from
  `users.role` over JDBC, `/api/customer/**` customer-only and `/api/provider/**` provider-only. JSON 401 (not
  logged in) and 403 (wrong role or not the owner). CSRF protection on.
- **Customer:** browse/filter/paginate slots (SQL LIMIT/OFFSET, from M1), book (`POST /api/customer/appointments`),
  confirmation, my appointments (upcoming/history), owner-only cancel that reopens the slot.
- **Provider:** add slots tied to a service, remove own open slots (soft delete), view appointments booked with them.
- **Statuses:** BOOKED, CANCELLED, COMPLETED (a scheduled job, and shown immediately in queries).
- **Concurrency:** optimistic version check in a READ COMMITTED `@Transactional` attempt, retried up to 3 times in
  fresh transactions. The partial unique index `uq_appt_active_slot` stays as the database backstop.
- **Errors/validation:** Bean Validation on request bodies; global handler for 400/403/404/409; no stack traces.
- **Frontend:** Bootstrap 5 single-page app following the M1 wireframes, plus login, my appointments and the
  provider dashboard.
- **Persistence:** schema/seed no longer wipe data on startup; named Docker volume; upgrade from an M1 database works.
- **Docs:** README (API table, concurrency, tests, curl example), relational-schema.md updated, this file.

Commits (all pushed to `main`): `b3d859d` backend, `15b1584` tests, `f988e63` frontend, `3bff5da` README/compose,
plus this summary commit. No `milestone-2` tag yet: tag after you add the report PDF and video link.

### Test results
- `mvn -B package -Dspring.profiles.active=localdb` (local PostgreSQL 16, see decision #18): **67 tests, 0 failures,
  0 errors**, BUILD SUCCESS, on every commit.
  - Unit: BookingServiceTest 11, AppointmentServiceTest 8, ProviderSlotServiceTest 10, SlotServiceTest 2.
  - Integration (real PostgreSQL): ConcurrentBookingTest 11 (two-thread race ×10 + forced interleaving),
    BookingFlowIntegrationTest 9, AuthIntegrationTest 9, ApiIntegrationTest 7.
- In all 10 two-thread repetitions, the log shows a real version conflict ("Version conflict booking slot 1
  (attempt 1/3)"). The winner varied (jordan 6, sam 4), and the loser got 409.
- **GitHub Actions CI** (plain `mvn -B verify` with Testcontainers): green for `b3d859d`, `15b1584`, `f988e63`, `3bff5da`.
  See the Actions tab for the later commits.
- Manual browser check with headless Chromium: all customer and provider flows worked, no JS errors (decision #22).

### Not done / not verified
- ~~The `docker compose up --build` path was not run~~ **Done 2026-10-08:** after Docker Desktop was started,
  `docker compose down -v` + `docker compose up --build` worked and a curl login/book/cancel passed.
- **Report PDF and walkthrough video:** yours to write and record (course rule). The checklist below shows where
  each topic is in the code.
- Small known limits: two simultaneous bookings by the *same* customer of two *different* overlapping slots could
  both pass the overlap check (it is not version-protected; fixing it needs a lock per customer or an exclusion
  constraint). Same for a provider creating two overlapping slots at once with different start times. Appointment
  lists are capped at 200 rows and not paginated. The session principal keeps the BCrypt hash in server memory
  (never sent to the client).
- Submission zip `CMPE172_Milestone2_FirstName_LastName.zip`: build it once the report exists, for example
  `git archive -o CMPE172_Milestone2_Charlie_Banh.zip HEAD` and then add the PDF.

### Where things are (for your report and video)
| Topic | Code |
|---|---|
| Frontend → backend flow | `static/app.js` `api()` and `bookPage()` → `CustomerController.book` → `BookingService.book` → `SlotBooker.bookOnce` → `SlotRepository.markBooked` / `AppointmentRepository.insert` |
| Race condition + optimistic lock | `SlotRepository.markBooked` (SQL + comment), `SlotBooker.bookOnce` |
| Transaction + isolation level | `@Transactional(isolation = READ_COMMITTED)` on `SlotBooker.bookOnce`, `AppointmentService.cancel` |
| Retry | `BookingService.book` loop, `backOff` |
| DB backstop | `schema.sql` `uq_appt_active_slot`; `DuplicateKeyException` catch in `BookingService` |
| Two-thread test | `ConcurrentBookingTest` |
| Login / BCrypt / session / role from DB | `SecurityConfig`, `JdbcUserDetailsService`, `UserRepository.findByUsername`, `UserPrincipal` |
| 403 rules | `SecurityConfig.authorizeHttpRequests`; ownership in `AppointmentService.cancel`, `ProviderSlotService.remove` |
| Status codes | `GlobalExceptionHandler` |
| Validation | `BookingRequest`, `CreateSlotRequest` (`@NotNull`, `@Future`, `@Size`) + `@Valid` in controllers |
| Pagination | `SlotRepository.findOpen` (`LIMIT :limit OFFSET :offset`) |

## Decisions

Each entry: what was decided, and why.

1. **Frontend: Bootstrap 5 + plain JavaScript single-page app** (chosen by Charlie before the autonomous run).
   One static `index.html` + `app.js` calls the JSON REST API with `fetch()`. No Node/Vite build step; the same
   JSON API serves the UI and the tests.

2. **Concurrency control: optimistic version check** (not pessimistic `SELECT ... FOR UPDATE`).
   `availability_slots.version` already existed from Milestone 1 for this purpose. Booking runs
   `UPDATE ... SET status='BOOKED', version=version+1 WHERE id=? AND version=? AND status='OPEN'`;
   0 rows updated means another transaction won. No row locks are held while the user's request does its
   other reads, and it works the same on any SQL database.

3. **Isolation level: READ COMMITTED** (PostgreSQL's default), set explicitly with
   `@Transactional(isolation = Isolation.READ_COMMITTED)`. With a conditional UPDATE, PostgreSQL makes the
   second writer wait for the first one's row lock and then re-checks the WHERE clause against the committed
   row, so the loser cleanly gets 0 rows. REPEATABLE READ / SERIALIZABLE would instead abort the loser with a
   serialization error, which adds error handling without adding safety here.

4. **Database backstop kept:** the partial unique index `uq_appt_active_slot ON appointments(slot_id) WHERE
   status='BOOKED'` from Milestone 1 stays. If it ever fires, `DuplicateKeyException` is turned into a 409.

5. **Retry: up to 3 attempts, each in a new transaction**, with a short random back-off (10–50 ms × attempt).
   The retry loop is in `BookingService` (not transactional); each attempt is `SlotBooker.bookOnce()`
   (`@Transactional`). It is a separate bean so the call goes through Spring's transaction proxy. A retry
   re-reads the slot: if it is now BOOKED the customer gets 409 immediately; the retry only helps when the
   version moved for another reason (for example a cancel followed by a re-open). Retry is used for booking
   only. Cancel and remove use conditional updates and return 409 instead of retrying.

6. **The schema no longer drops tables on startup** (it did in Milestone 1). `schema.sql` uses
   `CREATE ... IF NOT EXISTS`, and each `seed.sql` insert runs only when its table is empty, so bookings
   survive restarts. Reset with `docker compose down -v`. Seed slot times are relative to the day the
   database was first created, so an old database eventually has only past slots; providers can add new ones
   (or reset the volume).

7. **Slot uniqueness ignores removed slots:** `UNIQUE (provider_id, start_time)` became a partial unique index
   `WHERE status <> 'REMOVED'`, so a provider can re-add a time they removed. `schema.sql` drops the old
   Milestone 1 constraint if it is there.

8. **Removing a slot is a soft delete** (`status='REMOVED'`), so past appointments keep their slot row. Only
   OPEN slots can be removed (BOOKED gives 409); removal uses the same version check, so it cannot overwrite a
   booking made a moment earlier.

9. **Slot length comes from the service:** a provider sends `serviceId` + `startTime`; `endTime = startTime +
   service.duration_minutes`. Overlapping slots for the same provider are rejected with 409.

10. **Login: Spring Security form login posting to `/api/auth/login`, server-side HTTP session** (cookie
    `JSESSIONID`). Responses are JSON (200 with the user, 401 on bad credentials). `JdbcUserDetailsService` loads
    the user row (BCrypt hash + role) with JDBC; `BCryptPasswordEncoder` checks the password. Spring's session
    fixation protection changes the session id on login. No OAuth/JWT.

11. **RBAC by URL prefix:** `/api/customer/**` needs `ROLE_CUSTOMER`, `/api/provider/**` needs
    `ROLE_PROVIDER`. Not logged in gives 401 (JSON), wrong role gives 403 (JSON). `BookingService` also checks
    the role (defense in depth). Ownership rules (cancel only your own appointment, remove only your own slot)
    are checked in the service layer and give 403.

12. **CSRF protection stays on.** The token goes out in a readable `XSRF-TOKEN` cookie and the page sends it
    back in the `X-XSRF-TOKEN` header (Spring Security's documented setup for JavaScript frontends; Spring
    Security 6.5 has no `csrf.spa()` shortcut).

13. **Status codes:** 400 invalid input (Bean Validation on request bodies, bad `scope`, slot/service mismatch,
    past start time for a new slot), 401 not logged in, 403 wrong role or not the owner, 404 unknown
    slot/appointment/service, 409 slot taken, overlapping booking or slot, already cancelled, or started.
    A past or already-started slot is 409 (state conflict), not 400.

14. **COMPLETED status:** a scheduled job (`AppointmentStatusJob`, every minute) stores COMPLETED on BOOKED
    appointments whose slot has ended. Queries also show such rows as COMPLETED right away, so the UI is
    correct between job runs. "Upcoming" = BOOKED and not yet ended; everything else is "history".

15. **Extra booking rule:** a customer cannot hold two BOOKED appointments that overlap in time (409).

16. **Booking needs the service id too** (`{slotId, serviceId, notes}`), matching "book an available slot for a
    chosen service". If the service is not the slot's service, the response is 400.

17. **`java.time.Clock` bean**, so unit tests can fix "now" for the past/future rules.

18. **Local test database (environment workaround).** Docker Desktop was not running, so Testcontainers could not
    start PostgreSQL on this machine. (Correction, 2026-10-08: I first thought WSL integration was off too. It was not;
    once Docker Desktop was started, plain `mvn -B package` ran with Testcontainers in WSL: 67 tests, 0 failures.) I installed PostgreSQL 16 inside
    WSL (port **5433**, so it does not collide with `docker compose`'s 5432; database/user/password
    `scheduler_test`) and added a test-only Spring profile `localdb`
    (`src/test/resources/application-localdb.yml`). Under that profile `TestcontainersConfig` is skipped.
    Builds in this run used `mvn -B package -Dspring.profiles.active=localdb`. Plain `mvn -B package` (CI, or
    with Docker running) still uses Testcontainers, unchanged. The WSL PostgreSQL service is enabled at boot;
    to remove it: `sudo apt remove postgresql`.

19. **Integration tests reset the database before every test** (TRUNCATE + re-run `seed.sql`, in
    `IntegrationTestBase`), so test order does not matter now that tests write data.

20. **Frontend pages follow the Milestone 1 wireframes** (grayscale panels, dark primary buttons): Home, Available
    Slots (filter + Prev/Next paging), Book Appointment (summary + notes), Confirmation, plus Login, My Appointments
    (Upcoming/History tabs with Cancel) and a provider page "My Availability" (add slot, remove open slots,
    appointments booked with me). Hash routing (`#/slots`, `#/book/7`, ...) in `static/app.js`; Bootstrap 5.3 from
    the jsDelivr CDN. Nav links show by role, but the server enforces access regardless of what the page shows.
    The confirmation page says the mentor can see the booking. The wireframe's "confirmation sent to your email"
    line was left out because notifications arrive in Milestone 3.

21. **Added `GET /api/slots/{id}`** (public; 404 unless the slot is OPEN and in the future) so the booking page can
    load a slot's details after a page refresh.

22. **UI checked in a real browser:** headless Chromium (Playwright, installed in a scratch folder outside the
    repo) ran browse → filter → book (redirected to login first) → confirmation → cancel → history, plus the provider
    add/duplicate (409)/remove flow, the wrong-role page, bad login, and a 390 px wide phone layout. No JavaScript
    errors. This was a manual check; it is not part of `mvn package`.

23. **docker-compose.yml got a named volume `pgdata`** for PostgreSQL. Without it, `docker compose down` followed by
    `up` started from an empty anonymous volume, which hid the "data survives restarts" change in #6.
    `docker compose down -v` is the documented reset.

24. **README rewritten for Milestone 2:** endpoint table with the role for each endpoint, a concurrency summary,
    a list of the test classes, the `localdb` test option, and a curl login/book example that handles CSRF. The
    report PDF and walkthrough video are yours to make (course rule on AI-written report text); the README only
    documents the code.
