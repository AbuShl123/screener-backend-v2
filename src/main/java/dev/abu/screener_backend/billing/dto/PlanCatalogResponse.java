package dev.abu.screener_backend.billing.dto;

import java.util.List;

/** The public catalog response: the resolved currency (declared once) plus the priced plans. */
public record PlanCatalogResponse(String currency, List<PlanDto> plans) {}
