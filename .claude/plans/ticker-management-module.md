# Plan: Ticker Management Module

## Context
Before any WebSocket connections or orderbook tracking can begin, the screener needs a populated, continuously refreshed list of eligible tickers. This module establishes that foundation: it fetches Binance spot and futures exchange info on startup and every 4 hours, applies the inclusion rules defined in CLAUDE.md, and stores the result in a thread-safe in-memory registry that all future modules (WebSocket managers, orderbook stores, snapshot scheduler) will read from.

It also lays the groundwork for all future Binance REST calls by introducing a generic `BinanceRestClient` and typed `@ConfigurationProperties` bindings.

---

## Files to Modify

### `pom.xml`
Add four dependencies:
```xml
<!-- WebClient — non-blocking HTTP client (MVC stays; webflux is client-only) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- LMAX Disruptor — needed imminently for the event pipeline -->
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- java-websocket — needed imminently for Binance stream connections -->
<dependency>
    <groupId>org.java-websocket</groupId>
    <artifactId>Java-WebSocket</artifactId>
    <version>1.5.7</version>
</dependency>

<!-- @ConfigurationProperties compile-time metadata generation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

### `src/main/resources/application.properties`
Delete this file. Replace with `application.yml` (see below).

### `ScreenerBackendApplication.java`
Add `@EnableScheduling` to the class annotation set.

---

## Files to Create

### Package Structure
```
dev.abu.screener_backend/
├── ScreenerBackendApplication.java            (modify: add @EnableScheduling)
├── config/
│   ├── BinanceApiProperties.java              (@ConfigurationProperties "screener.binance")
│   ├── TickerProperties.java                  (@ConfigurationProperties "screener.ticker")
│   ├── WebClientConfig.java                   (@Configuration — WebClient beans + @EnableConfigurationProperties)
│   └── SecurityConfig.java                    (@Configuration — permit-all placeholder)
├── binance/
│   └── api/
│       ├── BinanceRestClient.java             (@Component — generic spot+futures HTTP wrapper)
│       ├── BinanceApiException.java           (RuntimeException — wraps HTTP errors)
│       └── dto/
│           ├── ExchangeInfoResponse.java      (minimal POJO — symbols list only)
│           └── BinanceSymbolDto.java          (minimal POJO — symbol, status, contractType)
└── ticker/
    ├── Ticker.java                            (record — symbol, hasFutures, hasSpot)
    ├── TickerRegistry.java                    (@Component — AtomicReference<Map> store)
    ├── TickerService.java                     (@Service — fetch, filter, update registry)
    ├── TickerRefreshListener.java             (@Component — startup fetch via ApplicationReadyEvent)
    ├── TickerRefreshScheduler.java            (@Component — periodic @Scheduled refresh)
    └── TickerController.java                  (@RestController — GET /api/screener/tickers)
```

---

## `application.yml` (complete)
```yaml
spring:
  application:
    name: screener-backend
  main:
    # CRITICAL: prevents spring-boot-starter-webflux from hijacking the server
    # to Netty reactive. WebFlux is used only as a WebClient HTTP library.
    web-application-type: servlet

screener:
  binance:
    spot-base-url: https://api.binance.com
    futures-base-url: https://fapi.binance.com
    # Binance exchangeInfo responses can reach 1-2 MB; default 256KB codec buffer is too small.
    codec-buffer-size-mb: 2

  ticker:
    # ISO-8601 duration. fixedDelayString — next run starts this long AFTER previous completes.
    refresh-interval: PT4H
    futures-contract-type: PERPETUAL

  orderbook:
    price-filter-threshold: 0.30

  disruptor:
    shard-count: 4
    ring-buffer-size: 65536

  websocket:
    max-streams-per-connection: 1024
    spot-stream-url: wss://stream.binance.com:9443/stream
    futures-stream-url: wss://fstream.binance.com/stream
```

---

## Per-File Implementation Details

### `config/BinanceApiProperties.java`
```java
@ConfigurationProperties(prefix = "screener.binance")
public record BinanceApiProperties(
    String spotBaseUrl,
    String futuresBaseUrl,
    int codecBufferSizeMb
) {}
```

### `config/TickerProperties.java`
```java
@ConfigurationProperties(prefix = "screener.ticker")
public record TickerProperties(
    String refreshInterval,       // e.g. "PT4H"
    String futuresContractType    // e.g. "PERPETUAL"
) {}
```

### `config/WebClientConfig.java`
- `@Configuration`
- `@EnableConfigurationProperties({BinanceApiProperties.class, TickerProperties.class})` — registers both property records
- Creates two named `WebClient` beans:
  - `@Bean("spotWebClient")` — base URL from `BinanceApiProperties.spotBaseUrl()`
  - `@Bean("futuresWebClient")` — base URL from `BinanceApiProperties.futuresBaseUrl()`
- Both beans use `WebClient.builder().exchangeStrategies(...)` to set `maxInMemorySize(props.codecBufferSizeMb() * 1024 * 1024)`
- Both set `defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)`
- Javadoc: explain why codec buffer must be enlarged and why `web-application-type: servlet` is required

### `config/SecurityConfig.java`
- `@Configuration`, `@EnableWebSecurity`
- Single `@Bean SecurityFilterChain`:
  ```java
  http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .csrf(csrf -> csrf.disable());
  return http.build();
  ```
- Javadoc: mark explicitly as a placeholder; note that JWT auth will replace this

### `binance/api/BinanceApiException.java`
- `extends RuntimeException`
- Fields: `HttpStatusCode statusCode`, `String responseBody`
- Used by `BinanceRestClient` to wrap non-2xx responses

### `binance/api/BinanceRestClient.java`
- `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
- Constructor injects `@Qualifier("spotWebClient") WebClient spotClient` and `@Qualifier("futuresWebClient") WebClient futuresClient`
- Two public methods (generic — usable by any future module):
  ```java
  public <T> Mono<T> getSpot(String path, Class<T> responseType)
  public <T> Mono<T> getFutures(String path, Class<T> responseType)
  ```
- Both: `.get().uri(path).retrieve().onStatus(HttpStatusCode::isError, handleError).bodyToMono(type).doOnError(e -> log.warn(...))`
- `handleError`: maps response body + status → `Mono.error(new BinanceApiException(...))`
- **Never calls `.block()` here** — callers decide whether to block or subscribe

### `binance/api/dto/BinanceSymbolDto.java`
- Plain class (not record — Jackson needs mutable or `@JsonCreator`; plain class with `@JsonIgnoreProperties` is simpler)
- `@JsonIgnoreProperties(ignoreUnknown = true)` — Binance symbols have ~30+ fields; only map what's needed
- Fields: `String symbol`, `String status`, `String contractType`
- Lombok `@Getter` for accessor generation
- **`contractType` is null for all spot symbols** — any null-check logic must use `"PERPETUAL".equals(dto.getContractType())` (literal on left side, null-safe)

### `binance/api/dto/ExchangeInfoResponse.java`
- Plain class with `@JsonIgnoreProperties(ignoreUnknown = true)`
- Single field: `List<BinanceSymbolDto> symbols`
- Lombok `@Getter`

### `ticker/Ticker.java`
```java
/** Immutable descriptor of a Binance ticker eligible for screening. */
public record Ticker(String symbol, boolean hasFutures, boolean hasSpot) {}
```

### `ticker/TickerRegistry.java`
- `@Component`, `@Slf4j`
- Internal state: `AtomicReference<Map<String, Ticker>> registry = new AtomicReference<>(Collections.emptyMap())`
- **Why `AtomicReference` and not `ConcurrentHashMap`**: refresh replaces the entire dataset atomically — readers always see a complete consistent snapshot, never a partially-repopulated map
- Public API:
  - `void replace(Map<String, Ticker> tickers)` — wraps in `unmodifiableMap`, then `registry.set(...)`
  - `Map<String, Ticker> getAll()` — single `registry.get()`, lock-free
  - `Optional<Ticker> find(String symbol)` — O(1) lookup
  - `int size()` — convenience

### `ticker/TickerService.java`
- `@Service`, `@RequiredArgsConstructor`, `@Slf4j`
- Injects: `BinanceRestClient restClient`, `TickerRegistry registry`, `TickerProperties props`
- Single public method: `void refreshTickers()`
  1. `Mono<ExchangeInfoResponse> spotMono = restClient.getSpot("/api/v3/exchangeInfo", ExchangeInfoResponse.class)`
  2. `Mono<ExchangeInfoResponse> futuresMono = restClient.getFutures("/fapi/v1/exchangeInfo", ExchangeInfoResponse.class)`
  3. `Mono.zip(spotMono, futuresMono, (spot, futures) -> buildTickerMap(spot, futures))`
  4. `.blockOptional()` — null-safe; preserves old data if Mono completes empty
  5. Entire call wrapped in `try/catch(Exception e)` → `log.error("Ticker refresh failed — retaining existing data", e)` — **never rethrows**
- Private `Map<String, Ticker> buildTickerMap(ExchangeInfoResponse spot, ExchangeInfoResponse futures)`:
  1. Futures set: filter `status = "TRADING"` AND `"PERPETUAL".equals(contractType)` → `Set<String> futuresSymbols`
  2. Spot set: filter `status = "TRADING"` → `Set<String> spotSymbols`
  3. For each symbol in `futuresSymbols`: `new Ticker(symbol, true, spotSymbols.contains(symbol))`
  4. Spot-only tickers are silently excluded (not in `futuresSymbols`)
  5. Return `Collections.unmodifiableMap(result)`

### `ticker/TickerRefreshListener.java`
- `@Component`, `@RequiredArgsConstructor`, `@Slf4j`
- Implements `ApplicationListener<ApplicationReadyEvent>`
- Injects: `TickerService tickerService`, `TickerRegistry registry`
- `onApplicationEvent`: logs start, calls `tickerService.refreshTickers()`, logs completion with `registry.size()`
- **Why `ApplicationReadyEvent` not `@PostConstruct`**: fires after the full context (WebClient beans, embedded server) is initialized — the correct moment for the first network call

### `ticker/TickerRefreshScheduler.java`
- `@Component`, `@RequiredArgsConstructor`, `@Slf4j`
- Injects: `TickerService tickerService`
- Single method:
  ```java
  @Scheduled(fixedDelayString = "${screener.ticker.refresh-interval}")
  public void scheduledRefresh()
  ```
- `fixedDelayString` (not `fixedRateString`) — the 4-hour delay starts only after the previous fetch completes; prevents overlapping fetches
- ISO-8601 duration `PT4H` is supported natively by Spring Boot 3+ in `fixedDelayString`

### `ticker/TickerController.java`
- `@RestController`, `@RequestMapping("/api/screener")`, `@RequiredArgsConstructor`
- Injects: `TickerRegistry registry`
- `GET /api/screener/tickers` → returns `TickerSummaryResponse` (private nested record):
  ```java
  record TickerSummaryResponse(int total, int spotCount, int futuresCount, List<Ticker> tickers) {}
  ```
- Response: all tickers sorted by symbol, with counts derived from stream operations on `registry.getAll().values()`

---

## Critical Gotchas

| Risk | Mitigation |
|------|-----------|
| WebFlux hijacks server to Netty | `spring.main.web-application-type: servlet` in application.yml — MUST be present |
| Codec buffer overflow on large exchange info responses | `maxInMemorySize(2 * 1024 * 1024)` in WebClientConfig |
| NPE on `contractType` for spot symbols | Always use `"PERPETUAL".equals(dto.getContractType())` (literal on left) |
| Partial registry state during refresh | `AtomicReference.set()` is atomic — readers see old OR new, never mid-swap |
| `@Scheduled` silently inactive | `@EnableScheduling` on `ScreenerBackendApplication` is required |
| Startup fetch before beans ready | Use `ApplicationReadyEvent`, not `@PostConstruct` |

---

## Verification

1. Run `mvn spring-boot:run` — confirm Tomcat starts (not Netty), no startup errors
2. Check logs: `"Initial ticker refresh complete — N tickers loaded"` should appear within ~5 seconds
3. `GET http://localhost:8080/api/screener/tickers` → HTTP 200, JSON with `total`, `spotCount`, `futuresCount`, and `tickers` array
4. Spot-only tickers (e.g., stablecoins with no futures) must NOT appear in the list
5. Futures-only tickers should appear with `"hasSpot": false`
6. All returned tickers should have `"hasFutures": true` (invariant — all tracked tickers have futures)
7. No `contractType` other than `PERPETUAL` should appear (e.g., no `CURRENT_QUARTER` delivery contracts)
8. Reduce `screener.ticker.refresh-interval` to `PT10S` temporarily and confirm periodic refresh logs appear
