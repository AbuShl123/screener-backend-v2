package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.payment.*;
import dev.abu.screener_backend.payment.multicard.dto.MulticardInvoiceRequest;
import dev.abu.screener_backend.payment.multicard.dto.MulticardInvoiceResponse;
import dev.abu.screener_backend.payment.multicard.dto.MulticardPaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The Multicard implementation of {@link PaymentProvider}. It owns the major-units → tiyin conversion
 * at the edge, builds the invoice request (attaching {@code ofd} only when fiscalization is enabled),
 * and maps Multicard's {@code PaymentStatusEnum} onto {@link ProviderStatus}.
 */
@Component
@Slf4j
public class MulticardPaymentProvider implements PaymentProvider {

    public static final String ID = "multicard";
    private static final String NOT_FOUND_CODE = "ERROR_NOT_FOUND";

    private final MulticardClient client;
    private final MulticardProperties props;

    public MulticardPaymentProvider(MulticardClient client, PaymentProperties props) {
        this.client = client;
        this.props = props.multicard();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CheckoutSession createCheckout(Order order) {
        long amountTiyin = toTiyin(order.getAmount());
        MulticardInvoiceRequest request = new MulticardInvoiceRequest(
                props.storeId(),
                amountTiyin,
                order.getId().toString(),
                props.callbackUrl(),
                props.returnUrl(),
                null,
                (int) props.invoiceTtl().toSeconds(),
                props.lang(),
                buildOfd(order, amountTiyin)
        );
        MulticardInvoiceResponse.Data data = client.createInvoice(request);
        return new CheckoutSession(data.uuid(), data.checkoutUrl());
    }

    @Override
    public ProviderPayment fetchPayment(String providerUuid) {
        try {
            MulticardPaymentResponse.Data data = client.getPayment(providerUuid);
            return new ProviderPayment(
                    mapStatus(data.status()),
                    data.ps(),
                    data.totalAmount(),
                    data.receiptUrl(),
                    data.psResponseMsg());
        } catch (MulticardException e) {
            if (NOT_FOUND_CODE.equals(e.getCode())) {
                return new ProviderPayment(ProviderStatus.NOT_FOUND, null, null, null, e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public void cancelCheckout(String providerUuid) {
        client.cancelInvoice(providerUuid); // DELETE /payment/invoice/{uuid}; best-effort (client logs failures)
    }

    /** Converts canonical major-unit money to integer tiyin (1 UZS = 100 tiyin) — exact, no rounding. */
    public static long toTiyin(BigDecimal amountMajor) {
        return amountMajor.movePointRight(2).longValueExact();
    }

    private static ProviderStatus mapStatus(String status) {
        if (status == null) {
            return ProviderStatus.PENDING;
        }
        return switch (status) {
            case "success" -> ProviderStatus.SUCCESS;
            case "error" -> ProviderStatus.ERROR;
            case "revert" -> ProviderStatus.REVERT;
            // Undocumented but observed: an invoice becomes "cancelled" when its TTL elapses or it is
            // deleted before payment. The docs' PaymentStatusEnum omits it; reality wins. Guard both
            // spellings (docs use one 'l'; the observed value had two).
            case "cancelled", "canceled" -> ProviderStatus.CANCELED;
            case "draft", "progress", "billing" -> ProviderStatus.PENDING;
            default -> {
                log.warn("Unknown Multicard payment status '{}'; treating as PENDING", status);
                yield ProviderStatus.PENDING;
            }
        };
    }

    /**
     * Fiscalization block — attached only when {@code ofd-enabled} (off in sandbox/local).
     *
     * <p><strong>PLACEHOLDER PAYLOAD (go-to-production dependency):</strong> the line detail here
     * carries only {@code qty}/{@code price}/{@code total}/{@code name}. Real {@code mxik} /
     * {@code package_code} / tax-rate data is required before {@code ofd-enabled} is turned on against a
     * production merchant agreement — enabling it as-is sends incomplete fiscal data. Do not rely on this
     * in production until the real tax fields are wired in.
     */
    private Object buildOfd(Order order, long amountTiyin) {
        if (!Boolean.TRUE.equals(props.ofdEnabled())) {
            return null;
        }
        return List.of(Map.of(
                "qty", 1,
                "price", amountTiyin,
                "total", amountTiyin,
                "name", order.getPlan().getDisplayName()
        ));
    }
}
