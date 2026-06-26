package dev.abu.screener_backend.payment.multicard;

import dev.abu.screener_backend.billing.Plan;
import dev.abu.screener_backend.config.PaymentProperties;
import dev.abu.screener_backend.config.PaymentProperties.MulticardProperties;
import dev.abu.screener_backend.payment.CheckoutSession;
import dev.abu.screener_backend.payment.Order;
import dev.abu.screener_backend.payment.ProviderPayment;
import dev.abu.screener_backend.payment.ProviderStatus;
import dev.abu.screener_backend.payment.multicard.dto.MulticardInvoiceRequest;
import dev.abu.screener_backend.payment.multicard.dto.MulticardInvoiceResponse;
import dev.abu.screener_backend.payment.multicard.dto.MulticardPaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Multicard adapter: major-units → tiyin conversion, {@code ofd} attached only when
 * enabled, and the {@code PaymentStatusEnum} → {@link ProviderStatus} mapping (incl. NOT_FOUND from the
 * provider error code). The {@link MulticardClient} is a hand-rolled subclass — no Mockito.
 */
class MulticardPaymentProviderTest {

    @Test
    void toTiyinMultipliesByOneHundredExactly() {
        assertEquals(79_000_000L, MulticardPaymentProvider.toTiyin(new BigDecimal("790000")));
        assertEquals(800_000L, MulticardPaymentProvider.toTiyin(new BigDecimal("8000.00")));
        assertEquals(12_345L, MulticardPaymentProvider.toTiyin(new BigDecimal("123.45")));
    }

    @Test
    void ofdOmittedWhenDisabled() {
        AtomicReference<MulticardInvoiceRequest> captured = new AtomicReference<>();
        MulticardPaymentProvider provider = new MulticardPaymentProvider(capturingClient(captured), props(false));

        provider.createCheckout(order());

        assertNull(captured.get().ofd(), "ofd must be omitted when fiscalization is disabled");
        assertEquals(500_000L, captured.get().amount());
    }

    @Test
    void ofdAttachedWhenEnabled() {
        AtomicReference<MulticardInvoiceRequest> captured = new AtomicReference<>();
        MulticardPaymentProvider provider = new MulticardPaymentProvider(capturingClient(captured), props(true));

        provider.createCheckout(order());

        assertNotNull(captured.get().ofd(), "ofd must be attached when fiscalization is enabled");
    }

    @Test
    void createCheckoutReturnsProviderSession() {
        MulticardPaymentProvider provider = new MulticardPaymentProvider(
                capturingClient(new AtomicReference<>()), props(false));
        CheckoutSession session = provider.createCheckout(order());
        assertEquals("uuid-123", session.providerUuid());
        assertEquals("https://checkout", session.checkoutUrl());
    }

    @Test
    void statusMappingCoversAllCases() {
        assertEquals(ProviderStatus.SUCCESS, fetchWithStatus("success").status());
        assertEquals(ProviderStatus.ERROR, fetchWithStatus("error").status());
        assertEquals(ProviderStatus.REVERT, fetchWithStatus("revert").status());
        assertEquals(ProviderStatus.PENDING, fetchWithStatus("draft").status());
        assertEquals(ProviderStatus.PENDING, fetchWithStatus("progress").status());
        assertEquals(ProviderStatus.PENDING, fetchWithStatus("billing").status());
        // Undocumented-but-observed cancelled status, both spellings (E3).
        assertEquals(ProviderStatus.CANCELED, fetchWithStatus("cancelled").status());
        assertEquals(ProviderStatus.CANCELED, fetchWithStatus("canceled").status());
    }

    @Test
    void notFoundErrorMapsToNotFound() {
        MulticardClient client = new MulticardClient(null, props(false)) {
            @Override
            public MulticardPaymentResponse.Data getPayment(String uuid) {
                throw new MulticardException("not found", "ERROR_NOT_FOUND");
            }
        };
        ProviderPayment payment = new MulticardPaymentProvider(client, props(false)).fetchPayment("u");
        assertEquals(ProviderStatus.NOT_FOUND, payment.status());
    }

    // ---- helpers ----

    private ProviderPayment fetchWithStatus(String status) {
        MulticardClient client = new MulticardClient(null, props(false)) {
            @Override
            public MulticardPaymentResponse.Data getPayment(String uuid) {
                return new MulticardPaymentResponse.Data(uuid, status, "uzcard", 500_000L, null);
            }
        };
        return new MulticardPaymentProvider(client, props(false)).fetchPayment("u");
    }

    private static MulticardClient capturingClient(AtomicReference<MulticardInvoiceRequest> captured) {
        return new MulticardClient(null, props(false)) {
            @Override
            public MulticardInvoiceResponse.Data createInvoice(MulticardInvoiceRequest request) {
                captured.set(request);
                return new MulticardInvoiceResponse.Data("uuid-123", "https://checkout");
            }
        };
    }

    private static Order order() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setAmount(new BigDecimal("5000.00")); // 500000 tiyin
        Plan plan = new Plan();
        plan.setDisplayName("Monthly");
        order.setPlan(plan);
        return order;
    }

    private static PaymentProperties props(boolean ofdEnabled) {
        MulticardProperties mc = new MulticardProperties(
                "https://dev-mesh.multicard.uz", "app", "secret", "store-1",
                "https://cb", "https://ret", Duration.ofMinutes(30), "195.158.26.90", "ru", ofdEnabled);
        return new PaymentProperties(Duration.ofMinutes(1), mc);
    }
}
