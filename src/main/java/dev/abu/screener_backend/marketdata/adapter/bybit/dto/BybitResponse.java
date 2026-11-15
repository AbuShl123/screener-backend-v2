package dev.abu.screener_backend.marketdata.adapter.bybit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The envelope every Bybit v5 REST response arrives in:
 * {@code {"retCode":0,"retMsg":"OK","result":{…},"retExtInfo":{},"time":…}}.
 *
 * <p>Bybit reports API-level failures inside an HTTP 200 — a rate-limited request is
 * {@code retCode 10006} / {@code 10018} with a 200 — so the status alone never says whether
 * {@code result} is usable ({@code external-docs/bybit/bybit-api-contracts.md} §3.1).
 * {@code BybitRestClient} unwraps this.
 *
 * @param retCode {@code 0} on success, Bybit's error code otherwise. Boxed so that a body without
 *                one stays {@code null} instead of reading as success
 * @param retMsg  {@code "OK"} or a human-readable failure reason
 * @param result  the payload; present and shaped even on error (with an empty {@code list})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BybitResponse<T>(Integer retCode, String retMsg, T result) {}
