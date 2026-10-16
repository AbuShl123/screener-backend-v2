# Multicard Integration Notes

Reference docs for integrating the **Multicard** payment gateway into the screener backend
(planned: subscription billing — see `CLAUDE.md` → Future Work → Payment gateway).

> Local mirror of the Multicard payment gateway API documentation.
> **Do not fetch the web docs** — read the local files under `docs/` instead.
> Each file bundles the category overview + all its endpoint OpenAPI specs, and preserves
> the original source URL as an HTML comment above every section.
> The docs are in Russian (gateway is Uzbekistan-based); schemas/OpenAPI specs are language-neutral.

## Layout

```
multicard/
├── README.md   # START HERE — index, quick lookup, integration notes
├── fetch-docs.sh  # re-runnable fetch script (URL → local-file mapping)
└── docs/       # local mirror of Multicard API docs (overview + OpenAPI specs per category)
    ├── introduction.md
    ├── auth.md
    ├── payments-invoice.md       # hosted checkout page
    ├── card-binding-form.md      # card binding (form, no PCI DSS)
    ├── card-binding-api.md       # card binding (API, requires PCI DSS)
    ├── payments-partner-page.md  # charging cards on partner's own page
    ├── holds.md                  # authorize + capture
    ├── payouts.md                # disbursements to cards
    ├── additional-methods.md     # app info, requisites, registries
    └── schemas.md                # data models
```

## Basics

- **Auth model**: obtain a Bearer token via `/auth`, send it as `Authorization: Bearer {token}` on all subsequent requests.
- **Response envelope**: every response has a `success` boolean; payload in `data`, errors in `error{code, details}`.

## Quick Index — which file to read for each feature

| Feature area | File | Contains |
|--------------|------|----------|
| Getting started / endpoints / response format / test cards | [docs/introduction.md](docs/introduction.md) | Sandbox & prod URLs, test card, OTP, error envelope |
| **Authorization** — get a token | [docs/auth.md](docs/auth.md) | `POST /auth` — token issuance, role, expiry |
| **Hosted checkout** (simplest integration) — create invoice, redirect to Multicard page | [docs/payments-invoice.md](docs/payments-invoice.md) | Create/get/cancel invoice, fast pay (QR: Payme/Click/Uzum), success callback, webhooks |
| **Card binding via form** (no PCI DSS needed) | [docs/card-binding-form.md](docs/card-binding-form.md) | Get binding-page link, binding callback, check binding state, card info by token, PINFL ownership check, revoke card token |
| **Card binding via API** (requires PCI DSS) | [docs/card-binding-api.md](docs/card-binding-api.md) | Add card by raw card data, confirm binding (OTP), check card by number |
| **Payments on partner page** (charge bound cards / raw cards) | [docs/payments-partner-page.md](docs/payments-partner-page.md) | Pay by card token, pay by raw card data, split payment, payme/click/uzum pay, confirm payment, send fiscal link, refund (full), partial refund, get payment info |
| **Holds** (authorize then capture) | [docs/holds.md](docs/holds.md) | Create hold request, confirm hold (block funds), capture held funds, get hold info, cancel hold (unblock) |
| **Payouts** (disburse to Uzcard/Humo cards) | [docs/payouts.md](docs/payouts.md) | Create payout by card number, confirm payout, get payout info |
| **Additional methods** | [docs/additional-methods.md](docs/additional-methods.md) | App info (wallet balance), recipient bank requisites, payments registry, payouts history |
| **Schemas** (data models) | [docs/schemas.md](docs/schemas.md) | applicationModel, paymentModel, cardModel, splitRequest, ofdRequest, PaymentStatusEnum, PaymentServiceEnum, storeModel, merchantModel, billingFieldsModel, merchantAccount, taxReceiptModel, clearingModel |

## Choosing an integration path

- **Just need to accept a payment with minimal effort** → hosted checkout: [docs/payments-invoice.md](docs/payments-invoice.md). Create an invoice, redirect the user to `checkout_url`, handle the success callback / webhook.
- **Need to charge saved cards on your own UI** → bind the card ([docs/card-binding-form.md](docs/card-binding-form.md) for non-PCI, [docs/card-binding-api.md](docs/card-binding-api.md) for PCI DSS), then charge via [docs/payments-partner-page.md](docs/payments-partner-page.md) using the card token.
- **Need authorize-now / capture-later** → [docs/holds.md](docs/holds.md).
- **Need to send money out to cards** → [docs/payouts.md](docs/payouts.md).

## Environment

- Sandbox: `https://dev-mesh.multicard.uz/`
- Production: `https://mesh.multicard.uz/`
- Sandbox test card: `8600533364098829` exp `28/06`, OTP `112233`