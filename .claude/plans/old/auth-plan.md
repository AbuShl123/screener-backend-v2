# Auth & User Management — Design Plan

## Scope

**Building now**: user registration/login, JWT-based auth for HTTP endpoints, JWT-based auth for WebSocket connections, PostgreSQL-backed user storage.

**Not building now**: email verification, per-user classification rules, subscription plans, user roles beyond a single `USER` role, admin endpoints. The design must leave clean extension points for all of these.

---

## Thread Isolation Guarantee

The core concern is: will adding a PostgreSQL database and auth logic slow down orderbook processing?

**No. Here's why.**

The Disruptor consumer threads and HikariCP DB connection pool are completely separate thread pools managed by the JVM. They share no synchronized resources.

```
                              ┌─────────────────────────────────┐
  Binance WebSocket threads   │  Disruptor Consumer Threads (2) │
  (java-websocket callbacks)  │  OrderBook, OrderBookProcessor  │
          │                   │  OrderBookClassifier             │
          │ ring buffer        └─────────────────────────────────┘
          ▼                              ↑ no intersection
  ┌──────────────────────┐              │
  │  Disruptor Ring      │              │
  │  Buffers (sharded)   │              │
  └──────────────────────┘              │
                               ┌────────┴────────────────────────┐
  HTTP requests (Tomcat)  ───► │  Tomcat Request Threads          │
  POST /api/auth/login         │  AuthController, AuthService     │
  GET  /api/auth/me            │  UserRepository → HikariCP → PG │
  WebSocket /ws (handshake)    └─────────────────────────────────┘
```

The only shared data structures (`OrderBookFeedStore`, `OrderBookStore`) are written by Disruptor threads and not touched by auth code at all. HikariCP I/O blocking a Tomcat thread has zero effect on Disruptor consumers.

---

## Tech Stack Additions

### New dependencies

```xml
<!-- PostgreSQL driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Spring Data JPA (brings HikariCP + Hibernate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Flyway — schema migrations -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>

<!-- Nimbus JOSE JWT — JWT generation and validation -->
<!-- Nimbus is Jackson-agnostic, no conflict with our tools.jackson 3.x -->
<dependency>
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.40</version>
</dependency>
```

**Why Nimbus JOSE JWT over JJWT**: JJWT's `jjwt-jackson` module depends on Jackson 2.x (`com.fasterxml.jackson`), which conflicts with this project's Jackson 3.x (`tools.jackson`). Nimbus JOSE JWT has its own JSON parsing; it does not depend on Jackson at all. It is also the library Spring Security uses internally for OAuth2/OIDC, so it is a known-good fit.

**Why not Spring Security OAuth2 Resource Server**: That module is designed for OAuth2 flows with external authorization servers. We control our own token issuance, so raw Nimbus is simpler and gives us full control without OAuth2 ceremony.

**Spring Security** is already in the pom; `spring-boot-starter-security` stays.

**JPA and PostgreSQL** are already in the pom as commented-out dependencies — just uncomment them.

---

## User Entity

Deliberately minimal. Designed so every future field is an additive change.

```java
// user/User.java  (JPA entity)
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;   // BCrypt

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;         // USER only for now; ADMIN added later

    @Column(nullable = false)
    private boolean enabled;       // future: email verification toggle

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
```

**Future fields that slot cleanly in**: `emailVerified`, `subscriptionPlan`, `stripeCustomerId`, `classificationConfig` (FK to a rules table), `lastLoginAt`.

### Flyway migration

`src/main/resources/db/migration/V1__create_users.sql` — standard Flyway naming. Schema migrations are checked at startup; no manual DDL needed.

Refresh tokens get their own table in a follow-up migration rather than cluttering `users`.

---

## JWT Token Strategy

### Two-token model (access + refresh)

| Token | Lifetime | Stored | Purpose |
|-------|----------|--------|---------|
| Access token | 15 minutes | Client only (memory/localStorage) | Authenticates API calls |
| Refresh token | 7 days | PostgreSQL (hashed) + client | Gets a new access token after expiry |

**Why store refresh tokens in DB**: Allows logout (invalidation), detects token reuse if stolen, supports "log out all devices". A pure stateless refresh token can never be revoked — not acceptable for a user-facing product.

**Refresh token table**:
```sql
-- V2__create_refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,   -- SHA-256 of the raw token
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON refresh_tokens(user_id);
```

One active refresh token per user (replace on each login). Future: support multiple sessions with per-device tokens.

### JWT claims (access token)

```json
{
  "sub":   "user-uuid-here",
  "email": "user@example.com",
  "role":  "USER",
  "iat":   1716000000,
  "exp":   1716000900
}
```

`sub` is the UUID (stable, not email which can change). `role` is embedded so Spring Security can build authorities without a DB call per request.

### Signing

HMAC-SHA256 with a 256-bit secret configured via `application.yml`. For production: use RS256 (RSA key pair) so the public key can be shared with future microservices. For now HS256 is simpler and sufficient.

---

## HTTP Endpoint Auth

### Spring Security filter chain (replaces placeholder SecurityConfig)

```java
// config/SecurityConfig.java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()   // register, login, refresh
            .requestMatchers("/api/screener/**").authenticated()
            .anyRequest().authenticated()
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

### JwtAuthenticationFilter

A `OncePerRequestFilter`:
1. Extract `Authorization: Bearer <token>` header
2. If absent or malformed, call `chain.doFilter()` — unauthenticated request proceeds to Spring Security's decision point
3. Validate JWT with Nimbus (`JWSVerifier`)
4. Parse claims, build `UsernamePasswordAuthenticationToken` with authorities from `role` claim
5. Set it on `SecurityContextHolder` — Spring Security sees an authenticated principal

**No DB call per request.** All needed information (userId, role) is in the JWT claims. The DB is only hit on login and refresh.

---

## WebSocket Auth

### The constraint

The outbound WebSocket endpoint uses `@ServerEndpoint` (JSR-356 / Jakarta WebSocket), not Spring's WebSocket. Spring Security's WebSocket support works only with Spring's WebSocket stack. **We cannot apply Spring Security filters to `@ServerEndpoint` connections.**

Additionally, browsers' native `WebSocket` API does not support custom HTTP headers on the WebSocket upgrade request. The only reliable cross-client option is a query parameter.

### Chosen approach: query param + `@OnOpen` validation

Client connects:
```
ws://host/ws?token=<access-jwt>
```

In `@OnOpen`, before doing anything else:
```java
@OnOpen
public void onOpen(Session session) {
    // Validate token before creating the session
    List<String> tokens = session.getRequestParameterMap().get("token");
    if (tokens == null || tokens.isEmpty()) {
        closeUnauthorized(session, "Missing token");
        return;
    }
    AuthenticatedUser user = jwtService.validateAndExtract(tokens.get(0));
    if (user == null) {
        closeUnauthorized(session, "Invalid token");
        return;
    }

    // Auth passed — proceed exactly as before, but now we know who connected
    UserWebSocketSession userSession = new UserWebSocketSession(session, user.userId());
    session.getUserProperties().put("session", userSession);
    userSession.startSendLoop();
    broadcaster.addSession(userSession);
    log.debug("WebSocket opened: {} user={}", session.getId(), user.userId());
}

private void closeUnauthorized(Session session, String reason) {
    try {
        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
    } catch (IOException e) {
        log.debug("Error closing unauthorized session: {}", e.getMessage());
    }
}
```

### Why query param is acceptable here

- The JWT is an access token with a 15-minute lifetime — short window for exposure
- The WebSocket URL is not logged in browser history (only the page that opened it may be)
- Screener clients are typically your own frontend, not random browsers
- Alternative (first-message auth): the WebSocket is technically open for a brief window before auth, which is worse

### Future improvement

If a native app or non-browser client is used, support `Authorization` header as a fallback:
```java
// Check Authorization header first (non-browser clients)
HandshakeRequest handshakeRequest = (HandshakeRequest)
    session.getUserProperties().get("jakarta.websocket.endpoint.originalHandshakeRequest");
```
This Tomcat-specific property exposes the original HTTP upgrade request. Not relying on it for now since it is not JSR-356 standard.

---

## Package Layout

```
src/main/java/dev/abu/screener_backend/
├── auth/
│   ├── AuthController.java        POST /api/auth/register, /login, /refresh, /logout
│   ├── AuthService.java           register, login, refresh, logout logic
│   ├── JwtService.java            JWT generation + validation (Nimbus)
│   ├── JwtAuthenticationFilter.java  OncePerRequestFilter for HTTP requests
│   └── dto/
│       ├── RegisterRequest.java   {firstName, lastName, email, password}
│       ├── LoginRequest.java      {email, password}
│       └── AuthResponse.java      {accessToken, refreshToken, expiresIn}
├── user/
│   ├── User.java                  JPA entity
│   ├── UserRole.java              Enum: USER (ADMIN later)
│   ├── UserRepository.java        Spring Data JPA: findByEmail, existsByEmail
│   ├── RefreshToken.java          JPA entity
│   ├── RefreshTokenRepository.java findByTokenHash, deleteByUserId
│   └── UserService.java           loadUserByEmail (used by AuthService)
├── config/
│   ├── SecurityConfig.java        replace placeholder
│   ├── JwtProperties.java         secret, accessTokenExpiry, refreshTokenExpiry
│   └── ... (existing configs)
└── ws/
    └── ... (existing; UserWebSocketSession gets userId field)
```

---

## API Surface

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/auth/register` | Public | Create account |
| `POST` | `/api/auth/login` | Public | Get token pair |
| `POST` | `/api/auth/refresh` | Refresh token in body | New access token |
| `POST` | `/api/auth/logout` | Bearer access token | Invalidate refresh token |
| `GET` | `/api/auth/me` | Bearer access token | Get own profile |

All other existing endpoints (`/api/screener/**`) require a valid access token.

---

## Configuration Properties

```yaml
# application.yml additions
screener:
  jwt:
    secret: "${JWT_SECRET}"          # 256-bit base64-encoded, from env var
    access-token-expiry: 15m         # Duration
    refresh-token-expiry: 7d         # Duration

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/screener
    username: "${DB_USER}"
    password: "${DB_PASSWORD}"
    hikari:
      maximum-pool-size: 10          # 10 is plenty; auth is not high-frequency
      connection-timeout: 3000
  jpa:
    hibernate:
      ddl-auto: validate             # Flyway manages schema; Hibernate only validates
    open-in-view: false              # Disable OSIV — stateless API, no lazy loading traps
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

## What We Explicitly Leave Clean for the Future

| Future Feature | Extension Point Left Today |
|----------------|---------------------------|
| Email verification | `User.enabled` field (false until verified) |
| Multiple roles | `UserRole` enum (add `ADMIN`, `PREMIUM`) |
| Subscription plans | `User` entity FK to a `plans` table |
| Per-user classification rules | `UserWebSocketSession.userId` — available for rule lookup |
| RS256 token signing | `JwtService` abstracted behind an interface; swap signing key |
| Multiple refresh tokens per device | `refresh_tokens` table already keyed by `(user_id, token_hash)` |
| Audit logging | `User.createdAt`, `RefreshToken.createdAt` already present |

---

## Implementation Phases

### Phase 6a — Database + User Entity
1. Uncomment JPA/PostgreSQL/Flyway dependencies in `pom.xml`
2. Write `V1__create_users.sql` + `V2__create_refresh_tokens.sql`
3. Create `User`, `UserRole`, `RefreshToken` JPA entities
4. Create `UserRepository`, `RefreshTokenRepository`
5. Add `JwtProperties` config record + yml entries

### Phase 6b — JWT Service + Auth Endpoints
1. Implement `JwtService` (Nimbus-based: generate, validate, extract claims)
2. Implement `AuthService` (register with BCrypt hash, login, refresh, logout)
3. Implement `AuthController` with request/response DTOs
4. Write `JwtAuthenticationFilter`
5. Replace `SecurityConfig` placeholder with real filter chain

### Phase 6c — WebSocket Auth
1. Add `userId` field to `UserWebSocketSession`
2. Add JWT validation to `ScreenerWebSocketEndpoint.onOpen`
3. `JwtService` must be reachable from `ScreenerWebSocketEndpoint` (already Spring-managed)

### Phase 6d — Smoke Test
1. `POST /api/auth/register` → 201
2. `POST /api/auth/login` → 200 with token pair
3. `GET /api/screener/tickers` without token → 401
4. `GET /api/screener/tickers` with token → 200
5. `ws://host/ws?token=<valid>` → connected, receives orderbook data
6. `ws://host/ws?token=<invalid>` → 1008 close
7. `POST /api/auth/refresh` → new access token
8. `POST /api/auth/logout` → refresh token invalidated

---

## What This Design Does NOT Do

- No per-request DB calls on the hot path — JWT claims carry everything Spring Security needs
- No synchronization between auth code and Disruptor threads — they share zero mutable state
- No sessions in the HTTP layer — fully stateless, `SessionCreationPolicy.STATELESS`
- No Spring WebSocket support — `@ServerEndpoint` stays as-is; auth is in `@OnOpen`
- No changes to `OrderBook`, `OrderBookProcessor`, `OrderBookClassifier`, or any `binance/` package
