# Multicard — Оплата на странице Партнера / Payments on partner page

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-%D0%BD%D0%B0-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B5-%D0%BF%D0%B0%D1%80%D1%82%D0%BD%D0%B5%D1%80%D0%B0-4488547f0.md -->

# Оплата на странице Партнера

> В случае [привязки карты плательщика](#5b24565d-701d-4d8b-b41f-ed2f37feaf3d), допускается проведение списания через систему Партнера с помощью токена карты, либо путем передачи карточных данных (при наличие сертификата PCI DSS). С привязанных карт можно проводить как безакцептное списание, так и с подтверждением через SMS-код (OTP) - выбранный режим подтверждения транзакции зависит от настроек на стороне Multicard.

Транзакция проводится в два этапа:

1. Создание платежа. В ответе в поле uuid возвращается уникальный ID транзакции в системе Multicard. На данном этапе на карте не списываются и не блокируется средства.
    
2. Подтверждение платежа. Доступно двумя способами:
    
    1. Перенаправление пользователя по ссылке в поле checkout_url из ответа на создание платежа.
        
    2. Путем отправки [запроса на подтверждение платежа](#4db015c3-bf1a-4ea9-a570-dd8c2761c561). В URL необходимо передать полученный в первом запросе uuid-транзакции. Если включен режим безакцептного списания, то необходимо отправить пустое тело запроса, в противном случае - полученный пользователем SMS-код. По результатам исполнения данного запроса с карты клиента списываются средства.
        

### Описание модели платежной транзакции (PaymentModel)

| Поле | Описание | Тип |
| --- | --- | --- |
| uuid | Уникальный ID транзакции | string(36) |
| store_id | ID магазина партнера | integer |
| status | Статус транзакции. Смотри {StatusModel} | enum |
| ps | Платежная система/сервис | enum(uzcard, humo, visa, mastercard, payme, click, ..) |
| store_invoice_id | ID инвойса в системе партнера | string(255) |
| store_details | Детали платежа | string |
| card_token | Токен карты | string |
| card_pan | Маска карты | string |
| billing_id | Уникальный ID транзакции в системе Партнера | string(64) |
| phone | Последние 4 цифры номер телефона клиента | string(12) |
| split | Объект с информацией о расщеплении | object |
| device_details | Объект с информацией об устройстве клиента | object |
| commission_type | Тип комиссии за эквайринг. Up – комиссия взымается поверх переданной суммы (с клиента). Down – комиссия взымается с мерчанта | enum(up, down) |
| payment_amount | Сумма платежа в тийинах (копейках) | integer |
| total_amount | Сумма списания с карты клиента с учетом комиссии в тийинах | integer |
| commission_amount | Сумма комиссии в тийинах | integer |
| otp_hash | В случае необходимости подтверждения платежа не равен null | nullable string |
| ps_uniq_id | Уникальный идентификатор платежа в платежной системе (ref_num, rrn). Указывается в банковской выписке по карте | string |
| ps_response_code | Код ответа платежной системы | string |
| callback_message | Ответ из биллинга партнера (в случае интеграции с биллингом) | string |
| tax | Объект с информацией о фискальном чеке. Ссылка на чек в поле qr_url | object |
| refund_tax | Объект с информацией о возвратном фискальном чеке. Ссылка на чек в поле qr_url | object |
| receipt_url | Ссылка на квитанцию об оплате | string |
| payment_time | Время списания средств с карты клиента | datetime (Y-m-d H:i:s) |
| refund_time | Время возврата | datetime (Y-m-d H:i:s) |
| store | Объект с информацией о магазине партнера | object |
| checkout_url | Ссылка на страницу проведения платежа | string |
| clearing | Объект с информацией о платежном поручении по возмещению на расчетный счет мерчанта | object |

Пример ответа:

``` json
{
    "success": true,
    "data": {
        "status": "draft",
        "ps": "humo",
        "store_id": 6,
        "store_invoice_id": "RB25047997032",
        "store_details": {
            "NDECREE": "RB25047997032",
            "SERVICE_TYPE": "01"
        },
        "card_token": "66b1f7d3565baf001c384f39",
        "card_pan": "409784******3066",
        "phone": "*3929",
        "split": null,
        "multicard_user_id": null,
        "device_details": null,
        "kyc_data": null,
        "callback_url": null,
        "ofd": null,
        "commission_type": "down",
        "terminal_id": "18610VQW",
        "merchant_id": "01200000012000N",
        "payment_amount": 100000,
        "total_amount": 100000,
        "commission_amount": 1500,
        "id": 52311181,
        "uuid": "2b163e87-6d7e-11f0-9a1b-00505680eaf6",
        "ps_uniq_id": null,
        "ps_response_code": null,
        "callback_message": null,
        "payment_time": null,
        "fee_munis": null,
        "refund_time": null,
        "billing_id": null,
        "otp_hash": null,
        "clearing_id": null,
        "tax_receipt_id": null,
        "added_on": "2025-07-31 00:48:30",
        "application": {
            "id": 9,
            "application_id": "multicard_cron",
            "wallet_sum": null,
            "wallet_sender_account": null,
            "wallet_overdraft": 0,
            "wallet_contract_num": null,
            "otp_required": 0,
            "otp_gateway": null,
            "sms_nickname": null,
            "allow_bank_transaction": 1,
            "otp_length": 6,
            "official_name": "Multicard",
            "offer_url": null,
            "phone": null,
            "allow_balance": 0,
            "added_on": "2021-08-12 00:58:02",
            "updated_on": "2025-03-28 21:04:17"
        },
        "store": {
            "id": 6,
            "uuid": "5a394898-d5aa-11ee-a37f-00505680eaf6",
            "category_id": null,
            "merchant_id": 5,
            "note": "путевка (тест)",
            "logo": "https://checkout.multicard.uz/img/favicon.png",
            "color": null,
            "uzum_terminal": "{\n    \"terminal_id\": \"1fb0f2ee-9460-48de-8a5e-3c7c036b394c\",\n    \"merchant_id\": null,\n    \"commission_type\": \"down\",\n    \"commission\": 0,\n    \"interest\": \"0\"\n}",
            "callback_class": null,
            "view_fields": null,
            "tax_registration": 0,
            "tax_mxik": "10204001001000000",
            "tax_package_code": "1500169",
            "tax_commission_recipient_tin": "200799456",
            "budget_category_id": null,
            "repeatable": 0,
            "otp_required": 0,
            "active": 1,
            "sort": 0,
            "cashback": 0,
            "tg_chat_id": null,
            "qr_url": null,
            "bg_img": null,
            "merchant_account_id": null,
            "title": "Multicard",
            "merchant": {
                "id": 5,
                "name": "Тестовый мерчант (Multicard)",
                "tin": "307578794",
                "contract_id": null,
                "bank_account": "00981:29896000900000981002",
                "sender_account": {
                    "ps_external_fee_type": "down",
                    "allow_sms": true,
                    "ps_external_fee_amount": "1.5",
                    "disable_deeplink": true,
                    "sbp_terminal_id": "90C4F76700644424AEBD1249DA2866DB"
                }
            },
            "surcharge": {
                "humo": 0,
                "uzcard": 0,
                "payme": 0,
                "click": 0,
                "uzum": 0,
                "alif": 0,
                "xazna": 0,
                "anorbank": 0,
                "oson": 0,
                "beepul": 0,
                "road24": 0
            },
            "discount": {
                "humo": 0,
                "uzcard": 0,
                "payme": 0,
                "click": 0,
                "uzum": 0,
                "alif": 0,
                "xazna": 0,
                "anorbank": 0,
                "oson": 0,
                "beepul": 0
            },
            "contract": null,
            "merchant_account": null
        },
        "tax": null,
        "refund_tax": null,
        "ps_response_msg": null,
        "receipt_url": null,
        "invoice": null,
        "details": {
            "NDECREE": "RB25047997032",
            "SERVICE_TYPE": "01"
        },
        "clearing": [],
        "bank": null,
        "checkout_url": "https://checkout.multicard.uz/2b163e87-6d7e-11f0-9a1b-00505680eaf6"
    }
}

 ```

### Возможные статусы транзакции (StatusModel)

| **Статус** | **Описание** | **Конечный** |
| --- | --- | --- |
| draft | Транзакция создана, но еще не подтверждена | Нет |
| progress | Транзакция подтверждена, в процессе исполнения | Нет |
| billing | Авторизация успешна, попытка отправки запроса в биллинг Мерчанта | Нет |
| hold | Средства захолдированы (заблокированы) на карте плательщика | Нет |
| success | Успешно | Да |
| error | Ошибка при списании или при отправки запроса в биллинг Мерчанта | Да |
| revert | Осуществлен возврат средств на карту клиента | Да |

В случае [привязки карты плательщика](#5b24565d-701d-4d8b-b41f-ed2f37feaf3d), допускается проведение списания через систему Партнера с помощью токена карты, либо путем передачи карточных данных (при наличие сертификата PCI DSS). С привязанных карт можно проводить как безакцептное списание, так и с подтверждением через SMS-код (OTP) - выбранный режим подтверждения транзакции зависит от настроек на стороне Multicard.

Транзакция проводится в два этапа:

1. Создание платежа. В ответе в поле uuid возвращается уникальный ID транзакции в системе Multicard. На данном этапе на карте не списываются и не блокируется средства.
    
2. Подтверждение платежа. Доступно двумя способами:
    
    1. Перенаправление пользователя по ссылке в поле checkout_url из ответа на создание платежа.
        
    2. Путем отправки [запроса на подтверждение платежа](#4db015c3-bf1a-4ea9-a570-dd8c2761c561). В URL необходимо передать полученный в первом запросе uuid-транзакции. Если включен режим безакцептного списания, то необходимо отправить пустое тело запроса, в противном случае - полученный пользователем SMS-код. По результатам исполнения данного запроса с карты клиента списываются средства.
        

### Описание модели платежной транзакции (PaymentModel)

| Поле | Описание | Тип |
| --- | --- | --- |
| uuid | Уникальный ID транзакции | string(36) |
| store_id | ID магазина партнера | integer |
| status | Статус транзакции. Смотри {StatusModel} | enum |
| ps | Платежная система/сервис | enum(uzcard, humo, visa, mastercard, payme, click, ..) |
| store_invoice_id | ID инвойса в системе партнера | string(255) |
| store_details | Детали платежа | string |
| card_token | Токен карты | string |
| card_pan | Маска карты | string |
| billing_id | Уникальный ID транзакции в системе Партнера | string(64) |
| phone | Последние 4 цифры номер телефона клиента | string(12) |
| split | Объект с информацией о расщеплении | object |
| device_details | Объект с информацией об устройстве клиента | object |
| commission_type | Тип комиссии за эквайринг. Up – комиссия взымается поверх переданной суммы (с клиента). Down – комиссия взымается с мерчанта | enum(up, down) |
| payment_amount | Сумма платежа в тийинах (копейках) | integer |
| total_amount | Сумма списания с карты клиента с учетом комиссии в тийинах | integer |
| commission_amount | Сумма комиссии в тийинах | integer |
| otp_hash | В случае необходимости подтверждения платежа не равен null | nullable string |
| ps_uniq_id | Уникальный идентификатор платежа в платежной системе (ref_num, rrn). Указывается в банковской выписке по карте | string |
| ps_response_code | Код ответа платежной системы | string |
| callback_message | Ответ из биллинга партнера (в случае интеграции с биллингом) | string |
| tax | Объект с информацией о фискальном чеке. Ссылка на чек в поле qr_url | object |
| refund_tax | Объект с информацией о возвратном фискальном чеке. Ссылка на чек в поле qr_url | object |
| receipt_url | Ссылка на квитанцию об оплате | string |
| payment_time | Время списания средств с карты клиента | datetime (Y-m-d H:i:s) |
| refund_time | Время возврата | datetime (Y-m-d H:i:s) |
| store | Объект с информацией о магазине партнера | object |
| checkout_url | Ссылка на страницу проведения платежа | string |
| clearing | Объект с информацией о платежном поручении по возмещению на расчетный счет мерчанта | object |

Пример ответа:

``` json
{
    "success": true,
    "data": {
        "status": "draft",
        "ps": "humo",
        "store_id": 6,
        "store_invoice_id": "RB25047997032",
        "store_details": {
            "NDECREE": "RB25047997032",
            "SERVICE_TYPE": "01"
        },
        "card_token": "66b1f7d3565baf001c384f39",
        "card_pan": "409784******3066",
        "phone": "*3929",
        "split": null,
        "multicard_user_id": null,
        "device_details": null,
        "kyc_data": null,
        "callback_url": null,
        "ofd": null,
        "commission_type": "down",
        "terminal_id": "18610VQW",
        "merchant_id": "01200000012000N",
        "payment_amount": 100000,
        "total_amount": 100000,
        "commission_amount": 1500,
        "id": 52311181,
        "uuid": "2b163e87-6d7e-11f0-9a1b-00505680eaf6",
        "ps_uniq_id": null,
        "ps_response_code": null,
        "callback_message": null,
        "payment_time": null,
        "fee_munis": null,
        "refund_time": null,
        "billing_id": null,
        "otp_hash": null,
        "clearing_id": null,
        "tax_receipt_id": null,
        "added_on": "2025-07-31 00:48:30",
        "application": {
            "id": 9,
            "application_id": "multicard_cron",
            "wallet_sum": null,
            "wallet_sender_account": null,
            "wallet_overdraft": 0,
            "wallet_contract_num": null,
            "otp_required": 0,
            "otp_gateway": null,
            "sms_nickname": null,
            "allow_bank_transaction": 1,
            "otp_length": 6,
            "official_name": "Multicard",
            "offer_url": null,
            "phone": null,
            "allow_balance": 0,
            "added_on": "2021-08-12 00:58:02",
            "updated_on": "2025-03-28 21:04:17"
        },
        "store": {
            "id": 6,
            "uuid": "5a394898-d5aa-11ee-a37f-00505680eaf6",
            "category_id": null,
            "merchant_id": 5,
            "note": "путевка (тест)",
            "logo": "https://checkout.multicard.uz/img/favicon.png",
            "color": null,
            "uzum_terminal": "{\n    \"terminal_id\": \"1fb0f2ee-9460-48de-8a5e-3c7c036b394c\",\n    \"merchant_id\": null,\n    \"commission_type\": \"down\",\n    \"commission\": 0,\n    \"interest\": \"0\"\n}",
            "callback_class": null,
            "view_fields": null,
            "tax_registration": 0,
            "tax_mxik": "10204001001000000",
            "tax_package_code": "1500169",
            "tax_commission_recipient_tin": "200799456",
            "budget_category_id": null,
            "repeatable": 0,
            "otp_required": 0,
            "active": 1,
            "sort": 0,
            "cashback": 0,
            "tg_chat_id": null,
            "qr_url": null,
            "bg_img": null,
            "merchant_account_id": null,
            "title": "Multicard",
            "merchant": {
                "id": 5,
                "name": "Тестовый мерчант (Multicard)",
                "tin": "307578794",
                "contract_id": null,
                "bank_account": "00981:29896000900000981002",
                "sender_account": {
                    "ps_external_fee_type": "down",
                    "allow_sms": true,
                    "ps_external_fee_amount": "1.5",
                    "disable_deeplink": true,
                    "sbp_terminal_id": "90C4F76700644424AEBD1249DA2866DB"
                }
            },
            "surcharge": {
                "humo": 0,
                "uzcard": 0,
                "payme": 0,
                "click": 0,
                "uzum": 0,
                "alif": 0,
                "xazna": 0,
                "anorbank": 0,
                "oson": 0,
                "beepul": 0,
                "road24": 0
            },
            "discount": {
                "humo": 0,
                "uzcard": 0,
                "payme": 0,
                "click": 0,
                "uzum": 0,
                "alif": 0,
                "xazna": 0,
                "anorbank": 0,
                "oson": 0,
                "beepul": 0
            },
            "contract": null,
            "merchant_account": null
        },
        "tax": null,
        "refund_tax": null,
        "ps_response_msg": null,
        "receipt_url": null,
        "invoice": null,
        "details": {
            "NDECREE": "RB25047997032",
            "SERVICE_TYPE": "01"
        },
        "clearing": [],
        "bank": null,
        "checkout_url": "https://checkout.multicard.uz/2b163e87-6d7e-11f0-9a1b-00505680eaf6"
    }
}

 ```

### Возможные статусы транзакции (StatusModel)

| **Статус** | **Описание** | **Конечный** |
| --- | --- | --- |
| draft | Транзакция создана, но еще не подтверждена | Нет |
| progress | Транзакция подтверждена, в процессе исполнения | Нет |
| billing | Авторизация успешна, попытка отправки запроса в биллинг Мерчанта | Нет |
| hold | Средства захолдированы (заблокированы) на карте плательщика | Нет |
| success | Успешно | Да |
| error | Ошибка при списании или при отправки запроса в биллинг Мерчанта | Да |
| revert | Осуществлен возврат средств на карту клиента | Да |


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%BF%D0%BE-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D1%83-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729310e0.md -->

# Создание платежа по токену карты

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment:
    post:
      summary: Создание платежа по токену карты
      deprecated: false
      description: >-
        В ответе возвращается {PaymentModel}. В случае, если в ответе поле
        otp_hash=null, то подтверждение платежа доступно без SMS-кода (для этого
        необходимо отправить [запрос на подтверждение
        платежа](#4db015c3-bf1a-4ea9-a570-dd8c2761c561) с пустым телом).
      tags:
        - Оплата на странице Партнера
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                card:
                  type: object
                  properties:
                    token:
                      type: string
                      description: |-
                        Max length:255
                        Токен карты для проведения платежных операций с картой
                  required:
                    - token
                  x-apidog-orders:
                    - token
                  x-apidog-ignore-properties: []
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                store_id:
                  type: integer
                  description: ID кассы Партнера в системе Multicard
                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                callback_url:
                  type: string
                  description: URL для отправки callback-запроса
                device_details:
                  type: object
                  properties:
                    ip:
                      type: string
                      description: >-
                        IP-адрес устройства клиента, с которого выполняется
                        запрос
                    user_agent:
                      type: string
                      description: >-
                        Cтрока User-Agent, содержащая информацию о браузере,
                        операционной системе и типе устройства клиента
                  required:
                    - ip
                    - user_agent
                  x-apidog-orders:
                    - ip
                    - user_agent
                  description: Объект с информацией об устройстве клиента
                  x-apidog-ignore-properties: []
                ofd:
                  description: Данные для формирования фискального чека
                  $ref: '#/components/schemas/ofdRequest'
              required:
                - card
                - amount
                - store_id
                - invoice_id
              x-apidog-orders:
                - card
                - amount
                - store_id
                - invoice_id
                - callback_url
                - device_details
                - ofd
              x-apidog-ignore-properties: []
            example:
              card:
                token: 6225f3c93f7a880142782fa4
              amount: 50000
              store_id: 6
              invoice_id: test
              callback_url: https://test.uz/callback
              device_details:
                ip: 177.14.322.11
                user_agent: >-
                  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)
                  AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0
                  Safari/537.36
              ofd:
                - qty: 1
                  price: 60000000
                  mxik: '06401004002000000'
                  total: 60000000
                  package_code: '1506113'
                  name: кроссовки men's low shoes
                - qty: 1
                  price: 55700000
                  mxik: '06401002004000000'
                  total: 55700000
                  package_code: '1519041'
                  name: кроссовки t.ace 2332 white black
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
                x-apidog-ignore-properties: []
              example: |-
                {
                    "success": true,
                    "data": {PaymentModel}
                }
          headers: {}
          x-apidog-name: PaymentModel
        '400':
          description: >-
            При отправке запроса с допущением ошибки валидации, возвращается
            ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: Необходимо заполнить «Amount».
          headers: {}
          x-apidog-name: Ошибочная валидация
        x-400:Создание платежа с неверным токеном карты:
          description: >-
            При попытке создании платежа с указанным неверным токеном карты,
            возвращается ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_CARD_NOT_FOUND
                  details: Неверный номер карты или срок действия
          headers: {}
          x-apidog-name: Создание платежа с неверным токеном карты
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729310-run
components:
  schemas:
    ofdRequest:
      type: array
      items:
        type: object
        properties:
          qty:
            type: integer
            description: Количество единиц товара/услуги
          vat:
            type: integer
            description: НДС (%)
          price:
            type: integer
            description: Стоимость единицы товара в тийинах
          mxik:
            type: string
            description: ИКПУ из справочника tasnif.soliq.uz
          total:
            type: integer
            description: Общая сумма товаров с учетом количества без учета скидок в тийинах
          package_code:
            type: string
            description: Код упаковки из справочника tasnif.soliq.uz
          name:
            type: string
            description: Наименование товара/услуги
          tin:
            type: string
            description: ИНН компании
          mark:
            type: array
            items:
              type: string
              description: Код маркировки
            description: >-
              Массив с кодами маркировок каждой единицы товара. Обязателен для
              маркировочных товаров
        x-apidog-orders:
          - qty
          - vat
          - price
          - mxik
          - total
          - package_code
          - name
          - tin
          - mark
        required:
          - qty
          - price
          - mxik
          - total
          - package_code
          - name
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D1%81-%D0%BF%D0%B5%D1%80%D0%B5%D0%B4%D0%B0%D1%87%D0%B5%D0%B9-%D0%BA%D0%B0%D1%80%D1%82%D0%BE%D1%87%D0%BD%D1%8B%D1%85-%D0%B4%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-19729311e0.md -->

# Создание платежа с передачей карточных данных

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment:
    post:
      summary: Создание платежа с передачей карточных данных
      deprecated: false
      description: >-
        Использование данного запроса доступно только при наличии у Партнера
        сертификата PCI DSS.


        В ответе возвращается {PaymentModel}.
      tags:
        - Оплата на странице Партнера
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                card:
                  type: object
                  properties:
                    pan:
                      type: string
                      description: Номер карты (Uzcard или Humo)
                    expiry:
                      type: string
                      description: Срок действия карты (yymm)
                  required:
                    - pan
                    - expiry
                  x-apidog-orders:
                    - pan
                    - expiry
                  x-apidog-ignore-properties: []
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                store_id:
                  type: integer
                  description: >-
                    ID (int) или UUID (string) кассы, выданный со стороны
                    Multicard
                invoice_id:
                  type: string
                  description: >-
                    Max length

                    :

                    255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежаи в кабинете мерчанта
                callback_url:
                  type: string
                  description: URL для отправки callback-запроса
                device_details:
                  type: object
                  properties:
                    ip:
                      type: string
                      description: >-
                        IP-адрес устройства клиента, с которого выполняется
                        запрос
                    user_agent:
                      type: string
                      description: >-
                        Cтрока User-Agent, содержащая информацию о браузере,
                        операционной системе и типе устройства клиента
                  required:
                    - ip
                    - user_agent
                  x-apidog-orders:
                    - ip
                    - user_agent
                  description: Объект с информацией об устройстве клиента
                  x-apidog-ignore-properties: []
                ofd:
                  description: Данные для формирования фискального чека
                  $ref: '#/components/schemas/ofdRequest'
              required:
                - card
                - amount
                - store_id
                - invoice_id
              x-apidog-orders:
                - card
                - amount
                - store_id
                - invoice_id
                - callback_url
                - device_details
                - ofd
              x-apidog-ignore-properties: []
            example:
              card:
                pan: '8600313260861293'
                expiry: '2602'
              amount: 50000
              store_id: 6
              invoice_id: test
              callback_url: https://test.uz/callback
              device_details:
                ip: 177.14.322.11
                user_agent: >-
                  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)
                  AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0
                  Safari/537.36
              ofd:
                - qty: 1
                  price: 60000000
                  mxik: '06401004002000000'
                  total: 60000000
                  package_code: '1506113'
                  name: кроссовки men's low shoes
                - qty: 1
                  price: 55700000
                  mxik: '06401002004000000'
                  total: 55700000
                  package_code: '1519041'
                  name: кроссовки t.ace 2332 white black
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
                x-apidog-ignore-properties: []
              example: |-
                {
                    "success": true,
                    "data": {PaymentModel}
                }
          headers: {}
          x-apidog-name: PaymentModel
        '400':
          description: >-
            При отправке запроса с допущением ошибки валидации, возвращается
            ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: >-
                    Card should be array with (`pan` and `expiry`) or `token`
                    field
          headers: {}
          x-apidog-name: Ошибочная валидация
        'x-400:Запрос с некорректным номером карты или срока действия ':
          description: >-
            При попытке добавления карты с неправильным номером, или сроком
            действия система возвращает ошибку с кодом 400, и сообщает клиенту
            об ошибке
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_CARD_NOT_FOUND
                  details: Неверный номер карты или срок действия
          headers: {}
          x-apidog-name: 'Запрос с некорректным номером карты или срока действия '
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729311-run
components:
  schemas:
    ofdRequest:
      type: array
      items:
        type: object
        properties:
          qty:
            type: integer
            description: Количество единиц товара/услуги
          vat:
            type: integer
            description: НДС (%)
          price:
            type: integer
            description: Стоимость единицы товара в тийинах
          mxik:
            type: string
            description: ИКПУ из справочника tasnif.soliq.uz
          total:
            type: integer
            description: Общая сумма товаров с учетом количества без учета скидок в тийинах
          package_code:
            type: string
            description: Код упаковки из справочника tasnif.soliq.uz
          name:
            type: string
            description: Наименование товара/услуги
          tin:
            type: string
            description: ИНН компании
          mark:
            type: array
            items:
              type: string
              description: Код маркировки
            description: >-
              Массив с кодами маркировок каждой единицы товара. Обязателен для
              маркировочных товаров
        x-apidog-orders:
          - qty
          - vat
          - price
          - mxik
          - total
          - package_code
          - name
          - tin
          - mark
        required:
          - qty
          - price
          - mxik
          - total
          - package_code
          - name
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D1%80%D0%B0%D1%81%D1%89%D0%B5%D0%BF%D0%BB%D0%B5%D0%BD%D0%BD%D0%BE%D0%B3%D0%BE-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-19729312e0.md -->

# Создание расщепленного платежа

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment:
    post:
      summary: Создание расщепленного платежа
      deprecated: false
      description: >-
        Пример запроса для расщепления платежа.


        Возможны следующие типы получателей (split\[\*\].type) при разделении
        платежа:  

        • account – перевод на р/с юридического лица (в recipient необходимо
        передать UIID банковских реквизитов (предоставляются со стороны
        Multicard);  

        • wallet – пополнение внутреннего кошелька Партнера. Аккумулированные на
        кошельке средства доступны к выводу на банковские карты, а также
        отправка средств на расчетный счет.


        Сумма значений amount в массиве split не должна превышать суммы оплаты
        за вычетом комиссии. К примеру, если сумма оплаты 1 000 сум, а комиссия
        составляет 2%, то общая сумма расщепления должна составлять 980 сум.


        В ответ возвращается {PaymentModel}
      tags:
        - Оплата на странице Партнера
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                card:
                  type: object
                  properties:
                    token:
                      type: string
                      description: |-
                        Max length:255
                        Токен карты для проведения платежных операций с картой
                  required:
                    - token
                  x-apidog-orders:
                    - token
                  x-apidog-ignore-properties: []
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                store_id:
                  type: integer
                  description: ID кассы Партнера в системе Multicard
                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                billing_id:
                  type: integer
                  description: Уникальный ID транзакции в системе Партнера
                callback_url:
                  type: string
                  description: URL для отправки callback-запроса
                ofd:
                  description: Данные для формирования фискального чека
                  $ref: '#/components/schemas/ofdRequest'
                split:
                  description: Объект с информацией о расщеплении
                  $ref: '#/components/schemas/splitRequest'
              required:
                - card
                - amount
                - store_id
                - invoice_id
              x-apidog-orders:
                - card
                - amount
                - store_id
                - invoice_id
                - billing_id
                - callback_url
                - ofd
                - split
              x-apidog-ignore-properties: []
            example:
              amount: 100000
              store_id: 6
              invoice_id: test
              billing_id: 1122334455
              callback_url: https://
              ofd:
                - qty: 1
                  vat: 12
                  price: 90000
                  mxik: '06401004002000000'
                  total: 90000
                  package_code: '1506113'
                  name: кроссовки men's low shoes
                  tin: '307578794'
                - qty: 1
                  vat: 0
                  price: 10000
                  mxik: '10107002001000000'
                  total: 10000
                  package_code: '1209885'
                  name: доставка заказа с маркетплейса
                  tin: '31105892390010'
              split:
                - type: account
                  recipient: 5378f655-ae41-11ee-97a8-005056b4367d
                  amount: 88000
                  details: 'Оплата товаров по заказу #123'
                - type: account
                  recipient: 7bd7ad8e-b2d5-11ee-97a8-005056b4367d
                  amount: 10000
                  details: 'Оплата за доставку по заказу #123'
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
                x-apidog-ignore-properties: []
              examples:
                '1':
                  summary: PaymentModel
                  value: |-
                    {
                        "success": true,
                        "data": {PaymentModel}
                    }
                '2':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_CARD_NOT_FOUND
                      details: Неверный номер карты или срок действия
                '3':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_FIELDS
                      details: Неверный store_id
          headers: {}
          x-apidog-name: PaymentModel
        '400':
          description: >-
            При попытке добавления карты с неправильным номером, или сроком
            действия система возвращает ошибку с кодом 400, и сообщает клиенту
            об ошибке
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: 'Запрос с некорректным номером карты или срока действия '
        x-400:Ошибочная валидация:
          description: >-
            При отправке запроса с допущением ошибки валидации, возвращается
            ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: Ошибочная валидация
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729312-run
components:
  schemas:
    splitRequest:
      type: array
      items:
        type: object
        properties:
          type:
            type: string
            enum:
              - account
              - wallet
              - card
            x-apidog-enum:
              - value: account
                name: ''
                description: Расчетный счет
              - value: wallet
                name: ''
                description: Депозит приложения
              - value: card
                name: ''
                description: Банковская карта
            description: Тип получателя
          amount:
            type: integer
            description: Сумма платежа
          details:
            type: string
            description: Детали платежа
          recipient:
            type: string
            description: >-
              Получатель. Если type=account, то передается uuid банковских
              реквизитов. По-умолчанию подставляются банковские реквизиты
              мерчанта.
        x-apidog-orders:
          - type
          - amount
          - details
          - recipient
        required:
          - type
          - amount
          - details
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    ofdRequest:
      type: array
      items:
        type: object
        properties:
          qty:
            type: integer
            description: Количество единиц товара/услуги
          vat:
            type: integer
            description: НДС (%)
          price:
            type: integer
            description: Стоимость единицы товара в тийинах
          mxik:
            type: string
            description: ИКПУ из справочника tasnif.soliq.uz
          total:
            type: integer
            description: Общая сумма товаров с учетом количества без учета скидок в тийинах
          package_code:
            type: string
            description: Код упаковки из справочника tasnif.soliq.uz
          name:
            type: string
            description: Наименование товара/услуги
          tin:
            type: string
            description: ИНН компании
          mark:
            type: array
            items:
              type: string
              description: Код маркировки
            description: >-
              Массив с кодами маркировок каждой единицы товара. Обязателен для
              маркировочных товаров
        x-apidog-orders:
          - qty
          - vat
          - price
          - mxik
          - total
          - package_code
          - name
          - tin
          - mark
        required:
          - qty
          - price
          - mxik
          - total
          - package_code
          - name
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-paymeclickuzum-%D0%B8-%D0%BF%D1%80%D0%BE%D1%87%D0%B8%D0%B5-19729313e0.md -->

# Создание платежа payme/click/uzum и прочие

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment:
    post:
      summary: Создание платежа payme/click/uzum и прочие
      deprecated: false
      description: >-
        Пример запроса на проведение оплаты через платежные приложения (Payme,
        Click, Uzumbank, Anorbank, Oson, Alif, Xazna, Beepul, Trastpay) без
        открытия промежуточной страницы оплаты Multicard. В ответе в поле
        checkout_url возвращается ссылка (Universal Link/App Link) на оплату в
        соответствующем платежном приложении, по которой необходимо
        перенаправить пользователя.


        При создании платежа с помощью данного запроса, отправка запроса
        подтверждение не требуется. Платеж завершится в случае оплаты
        пользователем в соответствующем платежном приложении.
      tags:
        - Оплата на странице Партнера
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                payment_system:
                  type: string
                  description: Платежная система через которую совершается платеж
                  enum:
                    - payme
                    - click
                    - uzum
                    - anorbank
                    - alif
                    - oson
                    - xazna
                    - beepul
                    - trastpay
                    - sbp
                  x-apidog-enum:
                    - value: payme
                      name: ''
                      description: ''
                    - value: click
                      name: ''
                      description: ''
                    - value: uzum
                      name: ''
                      description: ''
                    - value: anorbank
                      name: ''
                      description: ''
                    - value: alif
                      name: ''
                      description: ''
                    - value: oson
                      name: ''
                      description: ''
                    - value: xazna
                      name: ''
                      description: ''
                    - value: beepul
                      name: ''
                      description: ''
                    - value: trastpay
                      name: ''
                      description: ''
                    - value: sbp
                      name: ''
                      description: ''
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                store_id:
                  type: integer
                  description: ID кассы Партнера в системе Multicard
                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                billing_id:
                  type: integer
                  description: Уникальный ID транзакции в системе Партнера
                callback_url:
                  type: string
                  description: URL для отправки callback-запроса
                ofd: &ref_0
                  description: Данные для формирования фискального чека
                  $ref: '#/components/schemas/ofdRequest'
              required:
                - payment_system
                - amount
                - store_id
                - invoice_id
              x-apidog-orders:
                - payment_system
                - amount
                - store_id
                - invoice_id
                - billing_id
                - callback_url
                - ofd
              x-apidog-ignore-properties: []
            example:
              payment_system: payme
              amount: 50000
              store_id: 6
              invoice_id: test
              billing_id: 1122334455
              callback_url: https://
              ofd:
                - qty: 1
                  vat: 12
                  price: 60000000
                  mxik: '06401004002000000'
                  total: 60000000
                  package_code: '1506113'
                  name: кроссовки men's low shoes
                - qty: 1
                  vat: 12
                  price: 55700000
                  mxik: '06401002004000000'
                  total: 55700000
                  package_code: '1519041'
                  name: кроссовки t.ace 2332 white black
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                  data:
                    $ref: '#/components/schemas/paymentModel'
                x-apidog-orders:
                  - success
                  - data
                required:
                  - success
                  - data
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: PaymentModel
        '400':
          description: >-
            При отправке запроса с несуществующим типом внешних платежных
            систем, возвращается ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: Значение «Payment System» неверно.
          headers: {}
          x-apidog-name: Запрос с неправильной внешней системой платежа
        x-400:Ошибочная валидация:
          description: >-
            При отправке запроса с допущением ошибки валидации, возвращается
            ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: Необходимо заполнить «Amount».
          headers: {}
          x-apidog-name: Ошибочная валидация
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729313-run
components:
  schemas:
    ofdRequest:
      type: array
      items:
        type: object
        properties:
          qty:
            type: integer
            description: Количество единиц товара/услуги
          vat:
            type: integer
            description: НДС (%)
          price:
            type: integer
            description: Стоимость единицы товара в тийинах
          mxik:
            type: string
            description: ИКПУ из справочника tasnif.soliq.uz
          total:
            type: integer
            description: Общая сумма товаров с учетом количества без учета скидок в тийинах
          package_code:
            type: string
            description: Код упаковки из справочника tasnif.soliq.uz
          name:
            type: string
            description: Наименование товара/услуги
          tin:
            type: string
            description: ИНН компании
          mark:
            type: array
            items:
              type: string
              description: Код маркировки
            description: >-
              Массив с кодами маркировок каждой единицы товара. Обязателен для
              маркировочных товаров
        x-apidog-orders:
          - qty
          - vat
          - price
          - mxik
          - total
          - package_code
          - name
          - tin
          - mark
        required:
          - qty
          - price
          - mxik
          - total
          - package_code
          - name
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    paymentModel:
      type: object
      properties:
        id:
          type: integer
          description: ID транзакции в Multicard
        uuid:
          type: string
          format: uuid
          description: UUID транзакции в Multicard
        store_id:
          type: integer
          description: ID кассы, выданный со стороны Multicard
        payment_amount:
          type: integer
        commission_type:
          type: string
          enum:
            - up
            - down
          x-apidog-enum:
            - value: up
              name: ''
              description: Комиссия сверху (снимается с плательщика)
            - value: down
              name: ''
              description: Комиссия снизу (удерживается с мерчанта внутри платежа)
          description: Тип комиссии
        commission_amount:
          type: integer
        total_amount:
          type: integer
          description: Сумма платежа в тийинах
        store_invoice_id:
          type: string
          description: >-
            Любой идентификатор заказа в системе Партнера. Будет возвращен в
            callback-запросе. Также по нему можно искать платежаи в кабинете
            мерчанта
        status:
          $ref: '#/components/schemas/PaymentStatusEnum'
          description: Статус транзакции
        callback_url:
          type: string
          description: URL для отправки callback-запроса
          nullable: true
        billing_id:
          type: string
          description: Уникальный ID транзакции в системе Партнера
          nullable: true
        phone:
          type: string
          description: Телефон плательщика в формате 998XXXXXXXXX (при наличии)
          nullable: true
        ps:
          description: Платежный сервис/система
          $ref: '#/components/schemas/PaymentServiceEnum'
        receipt_url:
          type: string
          description: Ссылка на платежный чек
          nullable: true
        kyc_data:
          type: object
          properties:
            last_name:
              type: string
              description: Фамилия плательщика
            first_name:
              type: string
              description: Имя плательщика
            middle_name:
              type: string
              description: Отчество плательщика
            passport:
              type: string
              description: Паспорт плательщика
            dob:
              type: string
              description: Дата рождения плательщика (YYYY-MM-DD)
            passport_expiry_date:
              type: string
              description: Дата истечения паспорта плательщика
          x-apidog-orders:
            - last_name
            - first_name
            - middle_name
            - passport
            - dob
            - passport_expiry_date
          required:
            - last_name
            - first_name
            - middle_name
            - passport
            - dob
            - passport_expiry_date
          x-apidog-ignore-properties: []
          nullable: true
        device_details:
          type: object
          properties:
            ip:
              type: string
              description: IP-адрес устройства клиента, с которого выполняется запрос
            user_agent:
              type: string
              description: >-
                Cтрока User-Agent, содержащая информацию о браузере,
                операционной системе и типе устройства клиента
          x-apidog-orders:
            - ip
            - user_agent
          description: Объект с информацией об устройстве клиента
          required:
            - ip
            - user_agent
          x-apidog-ignore-properties: []
          nullable: true
        details:
          type: object
          properties: {}
          x-apidog-orders: []
          description: >-
            Поля, необходимые для проведения платежа в биллинге. Используется
            только при оплате за услуги Paynet и МУНИС (мобильная связь,
            гос.платежи и т.п.). Список полей и их название зависит от
            конкретной услуги (store_id)
          x-apidog-ignore-properties: []
          nullable: true
        card_token:
          type: string
          nullable: true
        card_pan:
          type: string
          nullable: true
        split:
          anyOf:
            - $ref: '#/components/schemas/splitRequest'
            - type: 'null'
        multicard_user_id:
          type: integer
          description: >-
            ID пользователя в приложении Multicard (если оплата проведена через
            приложение)
          nullable: true
        ofd:
          anyOf:
            - *ref_0
            - type: 'null'
        terminal_id:
          type: string
          description: ID терминала в платежной системе
          nullable: true
        merchant_id:
          type: string
          description: ID мерчанта в платежной системе
          nullable: true
        ps_uniq_id:
          type: string
          description: Reference number (RRN, RefNum) в платежной системе
          nullable: true
        ps_response_code:
          type: string
          description: Код ответа (ошибки) от платежной системы
          nullable: true
        ps_response_msg:
          type: string
          description: Описание ошибки от платежной системы
          nullable: true
        callback_message:
          type: string
          description: Текст ответа от биллинга мерчанта/поставщика
          nullable: true
        payment_time:
          type: string
          format: date-time
          description: >-
            Дата и время оплаты во временной зоне GMT+5 в формате YYYY-mm-dd
            H:s:s
          nullable: true
        refund_time:
          type: string
          format: date-time
          description: >-
            Дата и время возврата во временной зоне GMT+5 в формате YYYY-mm-dd
            H:s:s
        otp_hash:
          type: string
          description: >-
            Если значение не null, то требуется подтверждение транзакции через
            SMS-код (или 3DS)
          nullable: true
        clearing_id:
          type: integer
          nullable: true
        tax_receipt_id:
          type: integer
          nullable: true
        push_sent_at:
          type: string
          format: date-time
          nullable: true
        store:
          $ref: '#/components/schemas/storeModel'
        application:
          $ref: '#/components/schemas/applicationModel'
        tax:
          anyOf:
            - &ref_1
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_1
            - type: 'null'
        clearing:
          anyOf:
            - $ref: '#/components/schemas/clearingModel'
            - type: 'null'
        checkout_url:
          type: string
          description: URL страницы для оплаты
          nullable: true
        added_on:
          type: string
          format: date-time
      x-apidog-orders:
        - id
        - uuid
        - store_id
        - payment_amount
        - commission_type
        - commission_amount
        - total_amount
        - store_invoice_id
        - status
        - callback_url
        - billing_id
        - phone
        - ps
        - receipt_url
        - kyc_data
        - device_details
        - details
        - card_token
        - card_pan
        - split
        - multicard_user_id
        - ofd
        - terminal_id
        - merchant_id
        - ps_uniq_id
        - ps_response_code
        - ps_response_msg
        - callback_message
        - payment_time
        - refund_time
        - otp_hash
        - clearing_id
        - tax_receipt_id
        - push_sent_at
        - store
        - application
        - tax
        - refund_tax
        - clearing
        - checkout_url
        - added_on
      required:
        - id
        - uuid
        - store_id
        - payment_amount
        - commission_type
        - commission_amount
        - total_amount
        - store_invoice_id
        - status
        - callback_url
        - billing_id
        - phone
        - ps
        - receipt_url
        - kyc_data
        - device_details
        - details
        - card_token
        - card_pan
        - split
        - multicard_user_id
        - ofd
        - terminal_id
        - merchant_id
        - ps_uniq_id
        - ps_response_code
        - ps_response_msg
        - callback_message
        - payment_time
        - refund_time
        - otp_hash
        - clearing_id
        - tax_receipt_id
        - push_sent_at
        - store
        - application
        - tax
        - refund_tax
        - clearing
        - checkout_url
        - added_on
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    clearingModel:
      type: object
      properties:
        id:
          type: integer
        merchant: &ref_3
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_2
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_2
        purpose_code:
          type: string
          description: Код назначения платежа
        amount:
          type: integer
          description: Сумма платежа в тийинах
        details:
          type: string
          description: Детали платежа
        status:
          type: string
          enum:
            - new
            - sent
            - done
            - repeat
            - postponed
            - blocked
            - revert
            - canceled
          x-apidog-enum:
            - value: new
              name: ''
              description: ''
            - value: sent
              name: ''
              description: ''
            - value: done
              name: ''
              description: ''
            - value: repeat
              name: ''
              description: ''
            - value: postponed
              name: ''
              description: ''
            - value: blocked
              name: ''
              description: ''
            - value: revert
              name: ''
              description: ''
            - value: canceled
              name: ''
              description: ''
          description: Статус
        payment_time:
          type: string
          description: Время проведения платежа
        added_on:
          type: string
          description: Дата создания записи
        updated_on:
          type: string
          description: Дата изменения записи
        receipt_url:
          type: string
          description: URL на банковскую квитанцию
      x-apidog-orders:
        - id
        - merchant
        - recipient_info
        - sender_info
        - purpose_code
        - amount
        - details
        - status
        - payment_time
        - added_on
        - updated_on
        - receipt_url
      required:
        - id
        - merchant
        - recipient_info
        - sender_info
        - purpose_code
        - amount
        - details
        - status
        - payment_time
        - added_on
        - updated_on
        - receipt_url
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        official_name:
          type: string
          description: Наименование
        mfo:
          type: string
          description: МФО
        account_no:
          type: string
          description: Номер счета
        address:
          type: string
          description: Юридический адрес
          nullable: true
        director:
          type: string
          description: ФИО директора
          nullable: true
        director_pinfl:
          type: string
          description: ПИНФЛ директора
          nullable: true
        vat_payer:
          type: boolean
          description: Является ли плательщиков НДС
          nullable: true
        is_commitent:
          type: boolean
          description: Является ли комитентом Multicard
          nullable: true
        active:
          type: boolean
          description: >-
            Флаг активности. Если отключен, значит возмещение не будет
            отправлено
      x-apidog-orders:
        - id
        - uuid
        - tin
        - official_name
        - mfo
        - account_no
        - address
        - director
        - director_pinfl
        - vat_payer
        - is_commitent
        - active
      required:
        - id
        - uuid
        - tin
        - official_name
        - mfo
        - account_no
        - address
        - director
        - director_pinfl
        - vat_payer
        - is_commitent
        - active
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    merchantModel:
      type: object
      properties:
        id:
          type: integer
          description: ID клиента (мерчанта) в Multicard
        name:
          type: string
          description: Наименование клиента (мерчанта)
        tin:
          type: string
          description: ИНН мерчанта
        contract_id:
          type: string
          description: Данные о договоре
          nullable: true
        bank_account:
          type: string
          description: Транзитный счет для расчетов
          nullable: true
      x-apidog-orders:
        - id
        - name
        - tin
        - contract_id
        - bank_account
      required:
        - id
        - name
        - tin
        - contract_id
        - bank_account
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    taxReceiptModel:
      type: object
      properties:
        receipt:
          type: object
          properties: {}
          x-apidog-orders: []
          description: Объект чека, отправленный в ГНК
          x-apidog-ignore-properties: []
          nullable: true
        f_num:
          type: string
          format: uuid
          description: Фискальный признак
        fm_num:
          type: string
          description: Фискальный терминал
        qr_url:
          type: string
          description: URL на фискальный чек
          nullable: true
        is_refund:
          type: boolean
          description: Является ли чеком возврата
      x-apidog-orders:
        - receipt
        - f_num
        - fm_num
        - qr_url
        - is_refund
      required:
        - receipt
        - f_num
        - fm_num
        - qr_url
        - is_refund
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    applicationModel:
      type: object
      properties:
        id:
          type: integer
        application_id:
          type: string
        wallet_sum:
          type: integer
          nullable: true
        wallet_sender_account:
          type: string
          nullable: true
        wallet_overdraft:
          type: integer
        wallet_contract_num:
          type: string
          nullable: true
        otp_required:
          type: integer
        otp_gateway:
          type: string
          nullable: true
        sms_nickname:
          type: string
          nullable: true
      x-apidog-orders:
        - id
        - application_id
        - wallet_sum
        - wallet_sender_account
        - wallet_overdraft
        - wallet_contract_num
        - otp_required
        - otp_gateway
        - sms_nickname
      required:
        - id
        - application_id
        - wallet_sum
        - wallet_sender_account
        - wallet_overdraft
        - wallet_contract_num
        - otp_required
        - otp_gateway
        - sms_nickname
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    storeModel:
      type: object
      properties:
        id:
          type: integer
        uuid:
          type: string
          format: uuid
        category_id:
          type: integer
          nullable: true
        note:
          type: string
          nullable: true
        logo:
          type: string
          description: Логотип
          nullable: true
        color:
          type: string
          nullable: true
        view_fields:
          anyOf:
            - $ref: '#/components/schemas/billingFieldsModel'
            - type: 'null'
        tax_registration:
          type: integer
          enum:
            - 0
            - 1
            - 2
            - 3
          x-apidog-enum:
            - value: 0
              name: ''
              description: Фискализация отключена
            - value: 1
              name: ''
              description: Фискализация включена
            - value: 2
              name: ''
              description: Фискализируется только комиссия сверху
            - value: 3
              name: ''
              description: Фискализируется только комиссия снизу
          description: Флаг фискализации
        tax_mxik:
          type: string
          description: ИКПУ по умолчанию из каталога tasnif.soliq.uz
          nullable: true
        tax_package_code:
          type: string
          description: Код упаковки от ИКПУ по умолчанию из каталога tasnif.soliq.uz
          nullable: true
        tax_commission_recipient_tin:
          type: string
          description: ИНН комитента для фискализации. Если null, то берется ИНН мерчанта
          nullable: true
        tg_chat_id:
          type: string
          description: ID телеграм группы для отправки уведомлений о платежах
          nullable: true
        qr_url:
          type: string
          description: Ссылка на QR-код для приема платежей по данной кассе
        bg_img:
          type: string
          description: Фоновая картинка для страницы чекаута
        title:
          type: string
          description: Наименование кассы
        merchant: *ref_3
        contract:
          type: object
          properties:
            id:
              type: integer
            num:
              type: string
              description: Номер договора
            date:
              type: string
              format: date
              description: Дата договора
            service:
              type: string
            fee:
              type: object
              properties:
                up:
                  type: string
                down:
                  type: string
              x-apidog-orders:
                - up
                - down
              required:
                - up
                - down
              description: Комиссия по договору
              x-apidog-ignore-properties: []
            edm_document_id:
              type: string
              description: Идентификатор документа в системе электронного документооборота
              nullable: true
            edm_status:
              type: string
              description: >-
                Статус подписания документа в системе электронного
                документооборота
              nullable: true
          x-apidog-orders:
            - id
            - num
            - date
            - service
            - fee
            - edm_document_id
            - edm_status
          description: Информация о контракте с мерчантом
          required:
            - id
            - num
            - date
            - service
            - fee
            - edm_document_id
            - edm_status
          x-apidog-ignore-properties: []
          nullable: true
        merchant_account: *ref_2
      x-apidog-orders:
        - id
        - uuid
        - category_id
        - note
        - logo
        - color
        - view_fields
        - tax_registration
        - tax_mxik
        - tax_package_code
        - tax_commission_recipient_tin
        - tg_chat_id
        - qr_url
        - bg_img
        - title
        - merchant
        - contract
        - merchant_account
      required:
        - id
        - uuid
        - category_id
        - note
        - logo
        - color
        - view_fields
        - tax_registration
        - tax_mxik
        - tax_package_code
        - tax_commission_recipient_tin
        - tg_chat_id
        - qr_url
        - bg_img
        - title
        - merchant
        - contract
        - merchant_account
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    billingFieldsModel:
      type: array
      items:
        type: object
        properties:
          type:
            type: string
            description: Формат поля
            enum:
              - string
              - int
              - phone
              - tree
              - hidden
              - complex
              - select
            x-apidog-enum:
              - value: string
                name: ''
                description: ''
              - value: int
                name: ''
                description: ''
              - value: phone
                name: ''
                description: ''
              - value: tree
                name: ''
                description: ''
              - value: hidden
                name: ''
                description: ''
              - value: complex
                name: ''
                description: ''
              - value: select
                name: ''
                description: ''
          name:
            type: string
            description: Описание поля
          value:
            type: string
          key:
            type: string
            description: Ключ
          suggested:
            type: boolean
            description: Рекоммендуемая сумма оплаты
            nullable: true
        x-apidog-orders:
          - type
          - name
          - value
          - key
          - suggested
        required:
          - type
          - name
          - value
          - key
          - suggested
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    splitRequest:
      type: array
      items:
        type: object
        properties:
          type:
            type: string
            enum:
              - account
              - wallet
              - card
            x-apidog-enum:
              - value: account
                name: ''
                description: Расчетный счет
              - value: wallet
                name: ''
                description: Депозит приложения
              - value: card
                name: ''
                description: Банковская карта
            description: Тип получателя
          amount:
            type: integer
            description: Сумма платежа
          details:
            type: string
            description: Детали платежа
          recipient:
            type: string
            description: >-
              Получатель. Если type=account, то передается uuid банковских
              реквизитов. По-умолчанию подставляются банковские реквизиты
              мерчанта.
        x-apidog-orders:
          - type
          - amount
          - details
          - recipient
        required:
          - type
          - amount
          - details
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    PaymentServiceEnum:
      type: string
      enum:
        - uzcard
        - humo
        - visa
        - mastercard
        - account
        - payme
        - click
        - uzum
        - anorbank
        - oson
        - alif
        - xazna
        - beepul
        - trastpay
        - sbp
      x-apidog-enum:
        - value: uzcard
          name: ''
          description: ''
        - value: humo
          name: ''
          description: ''
        - value: visa
          name: ''
          description: ''
        - value: mastercard
          name: ''
          description: ''
        - value: account
          name: ''
          description: ''
        - value: payme
          name: ''
          description: ''
        - value: click
          name: ''
          description: ''
        - value: uzum
          name: ''
          description: ''
        - value: anorbank
          name: ''
          description: ''
        - value: oson
          name: ''
          description: ''
        - value: alif
          name: ''
          description: ''
        - value: xazna
          name: ''
          description: ''
        - value: beepul
          name: ''
          description: ''
        - value: trastpay
          name: ''
          description: ''
        - value: sbp
          name: ''
          description: ''
      description: Платежная система или сервис
      x-apidog-folder: ''
    PaymentStatusEnum:
      type: string
      enum:
        - draft
        - progress
        - billing
        - success
        - error
        - revert
      x-apidog-enum:
        - value: draft
          name: ''
          description: транзакция не подтверждена
        - value: progress
          name: ''
          description: транзакция в процессе списания в платежной системе
        - value: billing
          name: ''
          description: идет отправка запроса в биллинг поставщика
        - value: success
          name: ''
          description: успешно
        - value: error
          name: ''
          description: >-
            ошибка при списании или отправки запроса в биллинг поставщика.
            транзакция не успешна
        - value: revert
          name: ''
          description: возврат
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B6%D0%B4%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-19729314e0.md -->

# Подтверждение платежа

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{payment_uuid}:
    put:
      summary: Подтверждение платежа
      deprecated: false
      description: >-
        В случае получения ошибки с кодом **ERROR_DEBIT_UNKNOWN** или
        **ERROR_CALLBACK_TIMEOUT**, необходимо проверять статус платежа пока не
        будет получен конечный статус (success или error)
      tags:
        - Оплата на странице Партнера
      parameters:
        - name: payment_uuid
          in: path
          description: ''
          required: true
          example: 83e73262-b626-11f0-af7c-005056b4367d
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                otp:
                  type: string
                  description: Одноразовый пароль для подтверждения операции
                debit_available:
                  type: boolean
                  description: |-
                    Если передан true, в случае, если на карте 
                    недостаточно средств, будет списана 
                    доступная на карте сумма
              required:
                - otp
                - debit_available
              x-apidog-orders:
                - otp
                - debit_available
            example:
              otp: '50112'
              debit_available: false
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
              example: |-
                {
                    "success": true,
                    "data": {PaymentModel}
                }
          headers: {}
          x-apidog-name: Подтверждение платежа
        '400':
          description: При вводе некорректного код пароля, возвращается ошибку с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
              example:
                success: false
                error:
                  code: ERROR_WRONG_OTP
                  details: Неверный код подтверждения
          headers: {}
          x-apidog-name: Запрос с неправильным кодом подтверждения
        x-400:Повторный запрос после подтверждения платежа:
          description: >-
            При запросе подтверждения уже подтвержденного ранее платежа,
            возвращается ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: Платеж подтвержден ранее
          headers: {}
          x-apidog-name: Повторный запрос после подтверждения платежа
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729314-run
components:
  schemas: {}
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D0%BE%D1%82%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B0-%D1%84%D0%B8%D1%81%D0%BA%D0%B0%D0%BB%D1%8C%D0%BD%D0%BE%D0%B9-%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8-19729315e0.md -->

# Отправка фискальной ссылки

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{payment_uuid}/fiscal:
    patch:
      summary: Отправка фискальной ссылки
      deprecated: false
      description: >-
        Если фискализация платежа осуществляется на стороне Партнера, то
        необходима отправка фискальной ссылки в систему Multicard с помощью
        данного метода.
      tags:
        - Оплата на странице Партнера
      parameters:
        - name: payment_uuid
          in: path
          description: ''
          required: true
          example: 83e73262-b626-11f0-af7c-005056b4367d
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                url:
                  type: string
                  description: Ссылка на фискальный чек
                is_refund:
                  type: boolean
                  description: Является возвратным чеком
              required:
                - url
              x-apidog-orders:
                - url
                - is_refund
            example:
              url: https://ofd.soliq.uz/...
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
          headers: {}
          x-apidog-name: Success
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729315-run
components:
  schemas: {}
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D0%BE%D1%82%D0%BC%D0%B5%D0%BD%D0%B0-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%B2%D0%BE%D0%B7%D0%B2%D1%80%D0%B0%D1%82-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-19729316e0.md -->

# Отмена платежа (возврат средств)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{uuid}:
    delete:
      summary: Отмена платежа (возврат средств)
      deprecated: false
      description: ''
      tags:
        - Оплата на странице Партнера
      parameters:
        - name: uuid
          in: path
          description: ''
          required: true
          example: 375b518e-b622-11f0-af7c-005056b4367d
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties: {}
            example: ''
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
              example: |-
                {
                    "success": true,
                    "data": {PaymentModel}
                }
          headers: {}
          x-apidog-name: Отмена платежа (возврат средств)
        '404':
          description: >-
            При попытке отмены уже завершенного платежа, у которой оплата была
            проведена, возвращается ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
              example:
                success: false
                error:
                  code: ERROR_NOT_FOUND
                  details: Объект не найден
          headers: {}
          x-apidog-name: Отмена уже завершеногго платежа
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729316-run
components:
  schemas: {}
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D1%87%D0%B0%D1%81%D1%82%D0%B8%D1%87%D0%BD%D1%8B%D0%B9-%D0%B2%D0%BE%D0%B7%D0%B2%D1%80%D0%B0%D1%82-24802978e0.md -->

# Частичный возврат

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{uuid}/partial:
    delete:
      summary: Частичный возврат
      deprecated: false
      description: >-
        Для получения доступа к частичному возврату требуется настройка
        терминала со стороны Multicard
      tags:
        - Оплата на странице Партнера
      parameters:
        - name: uuid
          in: path
          description: ''
          required: true
          example: 375b518e-b622-11f0-af7c-005056b4367d
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              title: ''
              type: object
              properties:
                card_pan:
                  type: string
                  description: >-
                    Полный номер карты для возврата. Обязателен в случае оплаты
                    через платежное приложение (Payme, Click и т.д.). Если
                    оплата напрямую с карты Uzcard/Humo/Visa/Mastercard, то
                    передача не требуется
                ofd: &ref_0
                  $ref: '#/components/schemas/ofdRequest'
                  description: >-
                    Данные для фискализации нового чека. Старый фискальный чек
                    будет отменен.
                refund_amount:
                  type: integer
                  description: Сумма возврата (в тийинах)
              x-apidog-orders:
                - card_pan
                - ofd
                - refund_amount
              required:
                - ofd
                - refund_amount
              x-apidog-ignore-properties: []
            examples:
              '1':
                value: ''
              '2':
                value:
                  card_pan: '8600010000001234'
                  refund_amount: 22244100
                  ofd:
                    - name: Бензин марки A80
                      mxik: '02710001003000000'
                      package_code: 1282118
                      price: 795000
                      qty: 1
                      total: 1605900
                summary: Example 2
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: string
                  data:
                    $ref: '#/components/schemas/paymentModel'
                x-apidog-orders:
                  - success
                  - data
                required:
                  - success
                  - data
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: Отмена платежа (возврат средств)
        '404':
          description: >-
            При попытке отмены уже завершенного платежа, у которой оплата была
            проведена, возвращается ошибка с кодом 400
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: Статус запроса
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                        description: Кодовое сообщение
                      details:
                        type: string
                        description: Описание ошибки
                    required:
                      - code
                      - details
                    x-apidog-orders:
                      - code
                      - details
                    description: Ошибка
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - error
                x-apidog-orders:
                  - success
                  - error
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: Отмена уже завершеногго платежа
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-24802978-run
components:
  schemas:
    ofdRequest:
      type: array
      items:
        type: object
        properties:
          qty:
            type: integer
            description: Количество единиц товара/услуги
          vat:
            type: integer
            description: НДС (%)
          price:
            type: integer
            description: Стоимость единицы товара в тийинах
          mxik:
            type: string
            description: ИКПУ из справочника tasnif.soliq.uz
          total:
            type: integer
            description: Общая сумма товаров с учетом количества без учета скидок в тийинах
          package_code:
            type: string
            description: Код упаковки из справочника tasnif.soliq.uz
          name:
            type: string
            description: Наименование товара/услуги
          tin:
            type: string
            description: ИНН компании
          mark:
            type: array
            items:
              type: string
              description: Код маркировки
            description: >-
              Массив с кодами маркировок каждой единицы товара. Обязателен для
              маркировочных товаров
        x-apidog-orders:
          - qty
          - vat
          - price
          - mxik
          - total
          - package_code
          - name
          - tin
          - mark
        required:
          - qty
          - price
          - mxik
          - total
          - package_code
          - name
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    paymentModel:
      type: object
      properties:
        id:
          type: integer
          description: ID транзакции в Multicard
        uuid:
          type: string
          format: uuid
          description: UUID транзакции в Multicard
        store_id:
          type: integer
          description: ID кассы, выданный со стороны Multicard
        payment_amount:
          type: integer
        commission_type:
          type: string
          enum:
            - up
            - down
          x-apidog-enum:
            - value: up
              name: ''
              description: Комиссия сверху (снимается с плательщика)
            - value: down
              name: ''
              description: Комиссия снизу (удерживается с мерчанта внутри платежа)
          description: Тип комиссии
        commission_amount:
          type: integer
        total_amount:
          type: integer
          description: Сумма платежа в тийинах
        store_invoice_id:
          type: string
          description: >-
            Любой идентификатор заказа в системе Партнера. Будет возвращен в
            callback-запросе. Также по нему можно искать платежаи в кабинете
            мерчанта
        status:
          $ref: '#/components/schemas/PaymentStatusEnum'
          description: Статус транзакции
        callback_url:
          type: string
          description: URL для отправки callback-запроса
          nullable: true
        billing_id:
          type: string
          description: Уникальный ID транзакции в системе Партнера
          nullable: true
        phone:
          type: string
          description: Телефон плательщика в формате 998XXXXXXXXX (при наличии)
          nullable: true
        ps:
          description: Платежный сервис/система
          $ref: '#/components/schemas/PaymentServiceEnum'
        receipt_url:
          type: string
          description: Ссылка на платежный чек
          nullable: true
        kyc_data:
          type: object
          properties:
            last_name:
              type: string
              description: Фамилия плательщика
            first_name:
              type: string
              description: Имя плательщика
            middle_name:
              type: string
              description: Отчество плательщика
            passport:
              type: string
              description: Паспорт плательщика
            dob:
              type: string
              description: Дата рождения плательщика (YYYY-MM-DD)
            passport_expiry_date:
              type: string
              description: Дата истечения паспорта плательщика
          x-apidog-orders:
            - last_name
            - first_name
            - middle_name
            - passport
            - dob
            - passport_expiry_date
          required:
            - last_name
            - first_name
            - middle_name
            - passport
            - dob
            - passport_expiry_date
          x-apidog-ignore-properties: []
          nullable: true
        device_details:
          type: object
          properties:
            ip:
              type: string
              description: IP-адрес устройства клиента, с которого выполняется запрос
            user_agent:
              type: string
              description: >-
                Cтрока User-Agent, содержащая информацию о браузере,
                операционной системе и типе устройства клиента
          x-apidog-orders:
            - ip
            - user_agent
          description: Объект с информацией об устройстве клиента
          required:
            - ip
            - user_agent
          x-apidog-ignore-properties: []
          nullable: true
        details:
          type: object
          properties: {}
          x-apidog-orders: []
          description: >-
            Поля, необходимые для проведения платежа в биллинге. Используется
            только при оплате за услуги Paynet и МУНИС (мобильная связь,
            гос.платежи и т.п.). Список полей и их название зависит от
            конкретной услуги (store_id)
          x-apidog-ignore-properties: []
          nullable: true
        card_token:
          type: string
          nullable: true
        card_pan:
          type: string
          nullable: true
        split:
          anyOf:
            - $ref: '#/components/schemas/splitRequest'
            - type: 'null'
        multicard_user_id:
          type: integer
          description: >-
            ID пользователя в приложении Multicard (если оплата проведена через
            приложение)
          nullable: true
        ofd:
          anyOf:
            - *ref_0
            - type: 'null'
        terminal_id:
          type: string
          description: ID терминала в платежной системе
          nullable: true
        merchant_id:
          type: string
          description: ID мерчанта в платежной системе
          nullable: true
        ps_uniq_id:
          type: string
          description: Reference number (RRN, RefNum) в платежной системе
          nullable: true
        ps_response_code:
          type: string
          description: Код ответа (ошибки) от платежной системы
          nullable: true
        ps_response_msg:
          type: string
          description: Описание ошибки от платежной системы
          nullable: true
        callback_message:
          type: string
          description: Текст ответа от биллинга мерчанта/поставщика
          nullable: true
        payment_time:
          type: string
          format: date-time
          description: >-
            Дата и время оплаты во временной зоне GMT+5 в формате YYYY-mm-dd
            H:s:s
          nullable: true
        refund_time:
          type: string
          format: date-time
          description: >-
            Дата и время возврата во временной зоне GMT+5 в формате YYYY-mm-dd
            H:s:s
        otp_hash:
          type: string
          description: >-
            Если значение не null, то требуется подтверждение транзакции через
            SMS-код (или 3DS)
          nullable: true
        clearing_id:
          type: integer
          nullable: true
        tax_receipt_id:
          type: integer
          nullable: true
        push_sent_at:
          type: string
          format: date-time
          nullable: true
        store:
          $ref: '#/components/schemas/storeModel'
        application:
          $ref: '#/components/schemas/applicationModel'
        tax:
          anyOf:
            - &ref_1
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_1
            - type: 'null'
        clearing:
          anyOf:
            - $ref: '#/components/schemas/clearingModel'
            - type: 'null'
        checkout_url:
          type: string
          description: URL страницы для оплаты
          nullable: true
        added_on:
          type: string
          format: date-time
      x-apidog-orders:
        - id
        - uuid
        - store_id
        - payment_amount
        - commission_type
        - commission_amount
        - total_amount
        - store_invoice_id
        - status
        - callback_url
        - billing_id
        - phone
        - ps
        - receipt_url
        - kyc_data
        - device_details
        - details
        - card_token
        - card_pan
        - split
        - multicard_user_id
        - ofd
        - terminal_id
        - merchant_id
        - ps_uniq_id
        - ps_response_code
        - ps_response_msg
        - callback_message
        - payment_time
        - refund_time
        - otp_hash
        - clearing_id
        - tax_receipt_id
        - push_sent_at
        - store
        - application
        - tax
        - refund_tax
        - clearing
        - checkout_url
        - added_on
      required:
        - id
        - uuid
        - store_id
        - payment_amount
        - commission_type
        - commission_amount
        - total_amount
        - store_invoice_id
        - status
        - callback_url
        - billing_id
        - phone
        - ps
        - receipt_url
        - kyc_data
        - device_details
        - details
        - card_token
        - card_pan
        - split
        - multicard_user_id
        - ofd
        - terminal_id
        - merchant_id
        - ps_uniq_id
        - ps_response_code
        - ps_response_msg
        - callback_message
        - payment_time
        - refund_time
        - otp_hash
        - clearing_id
        - tax_receipt_id
        - push_sent_at
        - store
        - application
        - tax
        - refund_tax
        - clearing
        - checkout_url
        - added_on
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    clearingModel:
      type: object
      properties:
        id:
          type: integer
        merchant: &ref_3
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_2
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_2
        purpose_code:
          type: string
          description: Код назначения платежа
        amount:
          type: integer
          description: Сумма платежа в тийинах
        details:
          type: string
          description: Детали платежа
        status:
          type: string
          enum:
            - new
            - sent
            - done
            - repeat
            - postponed
            - blocked
            - revert
            - canceled
          x-apidog-enum:
            - value: new
              name: ''
              description: ''
            - value: sent
              name: ''
              description: ''
            - value: done
              name: ''
              description: ''
            - value: repeat
              name: ''
              description: ''
            - value: postponed
              name: ''
              description: ''
            - value: blocked
              name: ''
              description: ''
            - value: revert
              name: ''
              description: ''
            - value: canceled
              name: ''
              description: ''
          description: Статус
        payment_time:
          type: string
          description: Время проведения платежа
        added_on:
          type: string
          description: Дата создания записи
        updated_on:
          type: string
          description: Дата изменения записи
        receipt_url:
          type: string
          description: URL на банковскую квитанцию
      x-apidog-orders:
        - id
        - merchant
        - recipient_info
        - sender_info
        - purpose_code
        - amount
        - details
        - status
        - payment_time
        - added_on
        - updated_on
        - receipt_url
      required:
        - id
        - merchant
        - recipient_info
        - sender_info
        - purpose_code
        - amount
        - details
        - status
        - payment_time
        - added_on
        - updated_on
        - receipt_url
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        official_name:
          type: string
          description: Наименование
        mfo:
          type: string
          description: МФО
        account_no:
          type: string
          description: Номер счета
        address:
          type: string
          description: Юридический адрес
          nullable: true
        director:
          type: string
          description: ФИО директора
          nullable: true
        director_pinfl:
          type: string
          description: ПИНФЛ директора
          nullable: true
        vat_payer:
          type: boolean
          description: Является ли плательщиков НДС
          nullable: true
        is_commitent:
          type: boolean
          description: Является ли комитентом Multicard
          nullable: true
        active:
          type: boolean
          description: >-
            Флаг активности. Если отключен, значит возмещение не будет
            отправлено
      x-apidog-orders:
        - id
        - uuid
        - tin
        - official_name
        - mfo
        - account_no
        - address
        - director
        - director_pinfl
        - vat_payer
        - is_commitent
        - active
      required:
        - id
        - uuid
        - tin
        - official_name
        - mfo
        - account_no
        - address
        - director
        - director_pinfl
        - vat_payer
        - is_commitent
        - active
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    merchantModel:
      type: object
      properties:
        id:
          type: integer
          description: ID клиента (мерчанта) в Multicard
        name:
          type: string
          description: Наименование клиента (мерчанта)
        tin:
          type: string
          description: ИНН мерчанта
        contract_id:
          type: string
          description: Данные о договоре
          nullable: true
        bank_account:
          type: string
          description: Транзитный счет для расчетов
          nullable: true
      x-apidog-orders:
        - id
        - name
        - tin
        - contract_id
        - bank_account
      required:
        - id
        - name
        - tin
        - contract_id
        - bank_account
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    taxReceiptModel:
      type: object
      properties:
        receipt:
          type: object
          properties: {}
          x-apidog-orders: []
          description: Объект чека, отправленный в ГНК
          x-apidog-ignore-properties: []
          nullable: true
        f_num:
          type: string
          format: uuid
          description: Фискальный признак
        fm_num:
          type: string
          description: Фискальный терминал
        qr_url:
          type: string
          description: URL на фискальный чек
          nullable: true
        is_refund:
          type: boolean
          description: Является ли чеком возврата
      x-apidog-orders:
        - receipt
        - f_num
        - fm_num
        - qr_url
        - is_refund
      required:
        - receipt
        - f_num
        - fm_num
        - qr_url
        - is_refund
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    applicationModel:
      type: object
      properties:
        id:
          type: integer
        application_id:
          type: string
        wallet_sum:
          type: integer
          nullable: true
        wallet_sender_account:
          type: string
          nullable: true
        wallet_overdraft:
          type: integer
        wallet_contract_num:
          type: string
          nullable: true
        otp_required:
          type: integer
        otp_gateway:
          type: string
          nullable: true
        sms_nickname:
          type: string
          nullable: true
      x-apidog-orders:
        - id
        - application_id
        - wallet_sum
        - wallet_sender_account
        - wallet_overdraft
        - wallet_contract_num
        - otp_required
        - otp_gateway
        - sms_nickname
      required:
        - id
        - application_id
        - wallet_sum
        - wallet_sender_account
        - wallet_overdraft
        - wallet_contract_num
        - otp_required
        - otp_gateway
        - sms_nickname
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    storeModel:
      type: object
      properties:
        id:
          type: integer
        uuid:
          type: string
          format: uuid
        category_id:
          type: integer
          nullable: true
        note:
          type: string
          nullable: true
        logo:
          type: string
          description: Логотип
          nullable: true
        color:
          type: string
          nullable: true
        view_fields:
          anyOf:
            - $ref: '#/components/schemas/billingFieldsModel'
            - type: 'null'
        tax_registration:
          type: integer
          enum:
            - 0
            - 1
            - 2
            - 3
          x-apidog-enum:
            - value: 0
              name: ''
              description: Фискализация отключена
            - value: 1
              name: ''
              description: Фискализация включена
            - value: 2
              name: ''
              description: Фискализируется только комиссия сверху
            - value: 3
              name: ''
              description: Фискализируется только комиссия снизу
          description: Флаг фискализации
        tax_mxik:
          type: string
          description: ИКПУ по умолчанию из каталога tasnif.soliq.uz
          nullable: true
        tax_package_code:
          type: string
          description: Код упаковки от ИКПУ по умолчанию из каталога tasnif.soliq.uz
          nullable: true
        tax_commission_recipient_tin:
          type: string
          description: ИНН комитента для фискализации. Если null, то берется ИНН мерчанта
          nullable: true
        tg_chat_id:
          type: string
          description: ID телеграм группы для отправки уведомлений о платежах
          nullable: true
        qr_url:
          type: string
          description: Ссылка на QR-код для приема платежей по данной кассе
        bg_img:
          type: string
          description: Фоновая картинка для страницы чекаута
        title:
          type: string
          description: Наименование кассы
        merchant: *ref_3
        contract:
          type: object
          properties:
            id:
              type: integer
            num:
              type: string
              description: Номер договора
            date:
              type: string
              format: date
              description: Дата договора
            service:
              type: string
            fee:
              type: object
              properties:
                up:
                  type: string
                down:
                  type: string
              x-apidog-orders:
                - up
                - down
              required:
                - up
                - down
              description: Комиссия по договору
              x-apidog-ignore-properties: []
            edm_document_id:
              type: string
              description: Идентификатор документа в системе электронного документооборота
              nullable: true
            edm_status:
              type: string
              description: >-
                Статус подписания документа в системе электронного
                документооборота
              nullable: true
          x-apidog-orders:
            - id
            - num
            - date
            - service
            - fee
            - edm_document_id
            - edm_status
          description: Информация о контракте с мерчантом
          required:
            - id
            - num
            - date
            - service
            - fee
            - edm_document_id
            - edm_status
          x-apidog-ignore-properties: []
          nullable: true
        merchant_account: *ref_2
      x-apidog-orders:
        - id
        - uuid
        - category_id
        - note
        - logo
        - color
        - view_fields
        - tax_registration
        - tax_mxik
        - tax_package_code
        - tax_commission_recipient_tin
        - tg_chat_id
        - qr_url
        - bg_img
        - title
        - merchant
        - contract
        - merchant_account
      required:
        - id
        - uuid
        - category_id
        - note
        - logo
        - color
        - view_fields
        - tax_registration
        - tax_mxik
        - tax_package_code
        - tax_commission_recipient_tin
        - tg_chat_id
        - qr_url
        - bg_img
        - title
        - merchant
        - contract
        - merchant_account
      x-apidog-ignore-properties: []
      x-apidog-folder: ''
    billingFieldsModel:
      type: array
      items:
        type: object
        properties:
          type:
            type: string
            description: Формат поля
            enum:
              - string
              - int
              - phone
              - tree
              - hidden
              - complex
              - select
            x-apidog-enum:
              - value: string
                name: ''
                description: ''
              - value: int
                name: ''
                description: ''
              - value: phone
                name: ''
                description: ''
              - value: tree
                name: ''
                description: ''
              - value: hidden
                name: ''
                description: ''
              - value: complex
                name: ''
                description: ''
              - value: select
                name: ''
                description: ''
          name:
            type: string
            description: Описание поля
          value:
            type: string
          key:
            type: string
            description: Ключ
          suggested:
            type: boolean
            description: Рекоммендуемая сумма оплаты
            nullable: true
        x-apidog-orders:
          - type
          - name
          - value
          - key
          - suggested
        required:
          - type
          - name
          - value
          - key
          - suggested
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    splitRequest:
      type: array
      items:
        type: object
        properties:
          type:
            type: string
            enum:
              - account
              - wallet
              - card
            x-apidog-enum:
              - value: account
                name: ''
                description: Расчетный счет
              - value: wallet
                name: ''
                description: Депозит приложения
              - value: card
                name: ''
                description: Банковская карта
            description: Тип получателя
          amount:
            type: integer
            description: Сумма платежа
          details:
            type: string
            description: Детали платежа
          recipient:
            type: string
            description: >-
              Получатель. Если type=account, то передается uuid банковских
              реквизитов. По-умолчанию подставляются банковские реквизиты
              мерчанта.
        x-apidog-orders:
          - type
          - amount
          - details
          - recipient
        required:
          - type
          - amount
          - details
        x-apidog-ignore-properties: []
      x-apidog-folder: ''
    PaymentServiceEnum:
      type: string
      enum:
        - uzcard
        - humo
        - visa
        - mastercard
        - account
        - payme
        - click
        - uzum
        - anorbank
        - oson
        - alif
        - xazna
        - beepul
        - trastpay
        - sbp
      x-apidog-enum:
        - value: uzcard
          name: ''
          description: ''
        - value: humo
          name: ''
          description: ''
        - value: visa
          name: ''
          description: ''
        - value: mastercard
          name: ''
          description: ''
        - value: account
          name: ''
          description: ''
        - value: payme
          name: ''
          description: ''
        - value: click
          name: ''
          description: ''
        - value: uzum
          name: ''
          description: ''
        - value: anorbank
          name: ''
          description: ''
        - value: oson
          name: ''
          description: ''
        - value: alif
          name: ''
          description: ''
        - value: xazna
          name: ''
          description: ''
        - value: beepul
          name: ''
          description: ''
        - value: trastpay
          name: ''
          description: ''
        - value: sbp
          name: ''
          description: ''
      description: Платежная система или сервис
      x-apidog-folder: ''
    PaymentStatusEnum:
      type: string
      enum:
        - draft
        - progress
        - billing
        - success
        - error
        - revert
      x-apidog-enum:
        - value: draft
          name: ''
          description: транзакция не подтверждена
        - value: progress
          name: ''
          description: транзакция в процессе списания в платежной системе
        - value: billing
          name: ''
          description: идет отправка запроса в биллинг поставщика
        - value: success
          name: ''
          description: успешно
        - value: error
          name: ''
          description: >-
            ошибка при списании или отправки запроса в биллинг поставщика.
            транзакция не успешна
        - value: revert
          name: ''
          description: возврат
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B5-19729317e0.md -->

# Получение информации о платеже

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{uuid}:
    get:
      summary: Получение информации о платеже
      deprecated: false
      description: В ответе возвращается {PaymentModel}
      tags:
        - Оплата на странице Партнера
      parameters:
        - name: uuid
          in: path
          description: ''
          required: true
          example: 023874cd-b588-11f0-af7c-005056b4367
          schema:
            type: string
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
              example: |-
                {
                    "success": true,
                    "data": {PaymentModel}
                }
          headers: {}
          x-apidog-name: Подтверждение платежа Copy
      security: []
      x-apidog-folder: Оплата на странице Партнера
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729317-run
components:
  schemas: {}
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```

