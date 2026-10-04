package dev.abu.screener_backend.exchange.mexc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The envelope every MEXC futures REST response arrives in:
 * {@code {"success":true,"code":0,"data":...}}.
 *
 * <p>MEXC reports API-level failures inside a 2xx — a throttled request is HTTP 200 with
 * {@code {"success":false,"code":510,"message":"Requests are too frequent..."}} — so the HTTP
 * status alone never says whether {@code data} is usable. {@code MexcFuturesRestClient} unwraps this.
 *
 * @param success {@code false} on any API-level failure; {@code data} is then absent
 * @param code    {@code 0} on success, MEXC's error code otherwise (e.g. {@code 510} throttled)
 * @param message human-readable failure reason; absent on success
 * @param data    the payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MexcResponse<T>(boolean success, int code, String message, T data) {}
