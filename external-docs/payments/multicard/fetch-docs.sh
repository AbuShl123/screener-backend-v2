#!/usr/bin/env bash
set -euo pipefail

BASE="https://docs.multicard.uz"
OUT="external-docs/payments/multicard/docs"
mkdir -p "$OUT"

# fetch <slug> -> echoes content
fetch() {
  curl -sL --fail "$BASE/$1" || echo "FETCH_FAILED: $1"
}

# append <slug> <title> <outfile>
append() {
  local slug="$1" title="$2" file="$3"
  {
    echo ""
    echo "---"
    echo ""
    echo "<!-- source: $BASE/$slug -->"
    echo ""
    fetch "$slug"
    echo ""
  } >> "$OUT/$file"
}

# start <outfile> <heading>
start() {
  printf '# %s\n\n_Local copy of Multicard docs. Source URLs noted per section._\n' "$2" > "$OUT/$1"
}

############## introduction ##############
start introduction.md "Multicard — Introduction"
append "introduction-4736405f0.md" "Introduction" introduction.md

############## auth ##############
start auth.md "Multicard — Авторизация (Auth)"
append "%D0%B0%D0%B2%D1%82%D0%BE%D1%80%D0%B8%D0%B7%D0%B0%D1%86%D0%B8%D1%8F-4488543f0.md" "Авторизация (overview)" auth.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D0%B0-19729295e0.md" "Получение токена" auth.md

############## payments-invoice ##############
start payments-invoice.md "Multicard — Оплата на платежной странице (Invoice / Checkout)"
append "%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-%D0%BD%D0%B0-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%BD%D0%BE%D0%B9-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B5-multicard-4488544f0.md" "Оплата на платежной странице Multicard (overview)" payments-invoice.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B0-19729296e0.md" "Создание инвойса" payments-invoice.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%BD%D0%BE%D0%BC-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B5-19729297e0.md" "Получение информации о созданном инвойсе" payments-invoice.md
append "%D1%83%D0%B4%D0%B0%D0%BB%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B0%D0%BD%D0%BD%D1%83%D0%BB%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B0-19729298e0.md" "Удаление (аннулирование) инвойса" payments-invoice.md
append "%D0%B1%D1%8B%D1%81%D1%82%D1%80%D0%B0%D1%8F-%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-paymego-clickpass-%D0%B8-%D0%B4%D1%80-19729299e0.md" "Быстрая оплата (PaymeGo, ClickPass и др.)" payments-invoice.md
append "callback-success-19729300e0.md" "Callback (success)" payments-invoice.md
append "callback-webhooks-19729301e0.md" "Callback (webhooks)" payments-invoice.md

############## card-binding-form ##############
start card-binding-form.md "Multicard — Привязка карт (форма) / Card binding via form"
append "%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82-%D1%84%D0%BE%D1%80%D0%BC%D0%B0-4488545f0.md" "Привязка карт (форма) (overview)" card-binding-form.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8-%D0%BD%D0%B0-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D1%83-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729302e0.md" "Получение ссылки на страницу привязки карты" card-binding-form.md
append "callback-%D0%B7%D0%B0%D0%BF%D1%80%D0%BE%D1%81-19729303e0.md" "Callback-запрос" card-binding-form.md
append "%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D1%81%D0%BE%D1%81%D1%82%D0%BE%D1%8F%D0%BD%D0%B8%D1%8F-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729304e0.md" "Проверка состояния привязки карты" card-binding-form.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%BA%D0%B0%D1%80%D1%82%D0%B5-%D0%BF%D0%BE-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D1%83-19729305e0.md" "Получение информации о карте по токену" card-binding-form.md
append "%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%BF%D1%80%D0%B8%D0%BD%D0%B0%D0%B4%D0%BB%D0%B5%D0%B6%D0%BD%D0%BE%D1%81%D1%82%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BA-%D0%BF%D0%B8%D0%BD%D1%84%D0%BB-19729306e0.md" "Проверка принадлежности карты к ПИНФЛ" card-binding-form.md
append "%D0%B0%D0%BD%D0%BD%D1%83%D0%BB%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729307e0.md" "Аннулирование токена карты" card-binding-form.md

############## card-binding-api ##############
start card-binding-api.md "Multicard — Привязка карт (API) / Card binding via API (PCI DSS)"
append "%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82-api-4488546f0.md" "Привязка карт (API) (overview)" card-binding-api.md
append "%D0%B4%D0%BE%D0%B1%D0%B0%D0%B2%D0%BB%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D1%81-%D0%BF%D0%BE%D0%BC%D0%BE%D1%89%D1%8C%D1%8E-%D0%BA%D0%B0%D1%80%D1%82%D0%BE%D1%87%D0%BD%D1%8B%D1%85-%D0%B4%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-19729308e0.md" "Добавление карты с помощью карточных данных" card-binding-api.md
append "%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B6%D0%B4%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-19729309e0.md" "Подтверждение привязки" card-binding-api.md
append "%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BF%D0%BE-%D0%B5%D0%B5-%D0%BD%D0%BE%D0%BC%D0%B5%D1%80%D1%83-22557089e0.md" "Проверка карты по ее номеру" card-binding-api.md

############## payments-partner-page ##############
start payments-partner-page.md "Multicard — Оплата на странице Партнера / Payments on partner page"
append "%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-%D0%BD%D0%B0-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B5-%D0%BF%D0%B0%D1%80%D1%82%D0%BD%D0%B5%D1%80%D0%B0-4488547f0.md" "Оплата на странице Партнера (overview)" payments-partner-page.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%BF%D0%BE-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D1%83-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729310e0.md" "Создание платежа по токену карты" payments-partner-page.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D1%81-%D0%BF%D0%B5%D1%80%D0%B5%D0%B4%D0%B0%D1%87%D0%B5%D0%B9-%D0%BA%D0%B0%D1%80%D1%82%D0%BE%D1%87%D0%BD%D1%8B%D1%85-%D0%B4%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-19729311e0.md" "Создание платежа с передачей карточных данных" payments-partner-page.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D1%80%D0%B0%D1%81%D1%89%D0%B5%D0%BF%D0%BB%D0%B5%D0%BD%D0%BD%D0%BE%D0%B3%D0%BE-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-19729312e0.md" "Создание расщепленного платежа" payments-partner-page.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-paymeclickuzum-%D0%B8-%D0%BF%D1%80%D0%BE%D1%87%D0%B8%D0%B5-19729313e0.md" "Создание платежа payme/click/uzum и прочие" payments-partner-page.md
append "%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B6%D0%B4%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-19729314e0.md" "Подтверждение платежа" payments-partner-page.md
append "%D0%BE%D1%82%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B0-%D1%84%D0%B8%D1%81%D0%BA%D0%B0%D0%BB%D1%8C%D0%BD%D0%BE%D0%B9-%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8-19729315e0.md" "Отправка фискальной ссылки" payments-partner-page.md
append "%D0%BE%D1%82%D0%BC%D0%B5%D0%BD%D0%B0-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%B2%D0%BE%D0%B7%D0%B2%D1%80%D0%B0%D1%82-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-19729316e0.md" "Отмена платежа (возврат средств)" payments-partner-page.md
append "%D1%87%D0%B0%D1%81%D1%82%D0%B8%D1%87%D0%BD%D1%8B%D0%B9-%D0%B2%D0%BE%D0%B7%D0%B2%D1%80%D0%B0%D1%82-24802978e0.md" "Частичный возврат" payments-partner-page.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B5-19729317e0.md" "Получение информации о платеже" payments-partner-page.md

############## holds ##############
start holds.md "Multicard — Холдирование / Holds (authorize & capture)"
append "%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-4488548f0.md" "Холдирование (overview)" holds.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D1%82%D1%8C-%D0%B7%D0%B0%D1%8F%D0%B2%D0%BA%D1%83-%D0%BD%D0%B0-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-19729318e0.md" "Создать заявку на холдирование" holds.md
append "%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B4%D0%B8%D1%82%D1%8C-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B1%D0%BB%D0%BE%D0%BA%D0%B8%D1%80%D0%BE%D0%B2%D0%BA%D0%B0-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D0%B5-19729319e0.md" "Подтвердить холдирование" holds.md
append "%D1%81%D0%BF%D0%B8%D1%81%D0%B0%D1%82%D1%8C-%D0%B7%D0%B0%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%BD%D1%8B%D0%B5-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2%D0%B0-19729320e0.md" "Списать захолдированные средства" holds.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B8-19729321e0.md" "Получение информации о холдировании" holds.md
append "%D0%BE%D1%82%D0%BC%D0%B5%D0%BD%D0%B0-%D0%B7%D0%B0%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-%D0%B4%D0%BE-%D1%81%D0%BF%D0%B8%D1%81%D0%B0%D0%BD%D0%B8%D1%8F-19729322e0.md" "Отмена захолдированных средств (до списания)" holds.md

############## payouts ##############
start payouts.md "Multicard — Выплаты на карту (Payouts)"
append "%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%8B-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%83-payouts-4488549f0.md" "Выплаты на карту (overview)" payouts.md
append "%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%BD%D0%B0-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%83-c-%D0%BF%D0%B5%D1%80%D0%B5%D0%B4%D0%B0%D1%87%D0%B5%D0%B9-%D0%BD%D0%BE%D0%BC%D0%B5%D1%80%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729323e0.md" "Создание платежа на выплату c передачей номера карты" payouts.md
append "%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B4%D0%B8%D1%82%D1%8C-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%83-19729324e0.md" "Подтвердить выплату" payouts.md
append "%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D0%B5-19729325e0.md" "Получение информации о выплате" payouts.md

############## additional-methods ##############
start additional-methods.md "Multicard — Дополнительные методы (Additional methods)"
append "%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D1%8F-%D0%BE-%D0%BF%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B8-19729326e0.md" "Информация о приложении" additional-methods.md
append "%D1%80%D0%B5%D0%BA%D0%B2%D0%B8%D0%B7%D0%B8%D1%82%D1%8B-%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B0%D1%82%D0%B5%D0%BB%D1%8F-19729327e0.md" "Реквизиты получателя" additional-methods.md
append "%D1%80%D0%B5%D0%B5%D1%81%D1%82%D1%80-%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D0%B4%D0%B5%D0%BD%D0%BD%D1%8B%D1%85-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B5%D0%B9-19729328e0.md" "Реестр проведенных платежей" additional-methods.md
append "%D0%B8%D1%81%D1%82%D0%BE%D1%80%D0%B8%D1%8F-%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D0%B4%D0%B5%D0%BD%D0%BD%D1%8B%D1%85-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BF%D0%BE%D0%BF%D0%BE%D0%BB%D0%BD%D0%B5%D0%BD%D0%B8%D0%B9-19729329e0.md" "История проведенных выплат на карты" additional-methods.md

############## schemas ##############
start schemas.md "Multicard — Schemas (data models)"
append "applicationmodel-9357257d0.md" "applicationModel" schemas.md
append "paymentmodel-9357883d0.md" "paymentModel" schemas.md
append "cardmodel-9356533d0.md" "cardModel" schemas.md
append "splitrequest-9355022d0.md" "splitRequest" schemas.md
append "ofdrequest-9355028d0.md" "ofdRequest" schemas.md
append "paymentstatusenum-9416791d0.md" "PaymentStatusEnum" schemas.md
append "paymentserviceenum-9545152d0.md" "PaymentServiceEnum" schemas.md
append "storemodel-9655483d0.md" "storeModel" schemas.md
append "merchantmodel-9655500d0.md" "merchantModel" schemas.md
append "billingfieldsmodel-9655508d0.md" "billingFieldsModel" schemas.md
append "merchantaccount-9655769d0.md" "merchantAccount" schemas.md
append "taxreceiptmodel-9655813d0.md" "taxReceiptModel" schemas.md
append "clearingmodel-9655929d0.md" "clearingModel" schemas.md

echo "DONE"
grep -rl "FETCH_FAILED" "$OUT" || echo "No fetch failures."
