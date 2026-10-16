# Multicard — Выплаты на карту (Payouts)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%8B-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%83-payouts-4488549f0.md -->

# Выплаты на карту (payouts)

> Данные методы позволяют проводить выплаты (пополнения) на банковские карты Uzcard/Humo с депозита (кошелька) Партнера.

Проверить баланс депозита можно с помощью метода [получения информации о приложении](#5a8650f5-ab0f-455c-8129-11eb6ee54a8a).

Данные методы позволяют проводить выплаты (пополнения) на банковские карты Uzcard/Humo с депозита (кошелька) Партнера.

Проверить баланс депозита можно с помощью метода [Информация о приложении](https://docs.multicard.uz/%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D1%8F-%D0%BE-%D0%BF%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B8-19729326e0.md)


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B0-%D0%BD%D0%B0-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%83-c-%D0%BF%D0%B5%D1%80%D0%B5%D0%B4%D0%B0%D1%87%D0%B5%D0%B9-%D0%BD%D0%BE%D0%BC%D0%B5%D1%80%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729323e0.md -->

# Создание платежа на выплату c передачей номера карты

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/credit:
    post:
      summary: Создание платежа на выплату c передачей номера карты
      deprecated: false
      description: >-
        (!) При получении ошибки с кодом «ERROR_UNKNOWN» или таймаута на запрос,
        необходимо проверять статус транзакции до тех пор, пока не получен
        конечный (error или success)
      tags:
        - Выплаты на карту (payouts)
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
                    token:
                      type: string
                      description: Токен карты, полученный при првязке
                  x-apidog-orders:
                    - pan
                    - token
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                store_id:
                  type: string
                  description: ID кассы Партнера в системе Multicard
                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                confirmable:
                  type: boolean
                  description: >+
                    Если передать false или опустить данное поле, то пополнение
                    карты пройдет одним запросом (без подтверждения)

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
                kyc_data:
                  type: object
                  properties:
                    last_name:
                      type: string
                      description: Фамилия владельца карты
                    first_name:
                      type: string
                      description: Имя владельца карты
                    middle_name:
                      type: string
                      description: Отчество владельца карты
                    passport:
                      type: string
                      description: Паспорт владельца карты
                    pinfl:
                      type: string
                      description: ПИНФЛ владельца карты
                    dob:
                      type: string
                      description: Дата рождения владельца карты (YYYY-MM-DD)
                    passport_expiry_date:
                      type: string
                      description: Дата истечения паспорта владельца карты
                  required:
                    - last_name
                    - first_name
                    - middle_name
                    - passport
                    - pinfl
                    - dob
                    - passport_expiry_date
                  x-apidog-orders:
                    - last_name
                    - first_name
                    - middle_name
                    - passport
                    - pinfl
                    - dob
                    - passport_expiry_date
                  description: >+
                    Поле обязательно к заполнению в случае пополнения более 10
                    млн.сум

              required:
                - card
                - amount
                - store_id
                - invoice_id
                - confirmable
                - device_details
                - kyc_data
              x-apidog-orders:
                - card
                - amount
                - store_id
                - invoice_id
                - confirmable
                - device_details
                - kyc_data
            example:
              card:
                pan: '8600303655375959'
              amount: 10000
              store_id: 6
              invoice_id: '112233'
              confirmable: true
              device_details:
                ip: 182.19.100.10
                user_agent: >-
                  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)
                  AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0
                  Safari/537.36
              kyc_data:
                last_name: BOLTAYEV
                first_name: ALISHER
                middle_name: MUMINOVICH
                passport: AD21234567
                pinfl: '31105892514010'
                dob: '1989-05-11'
                passport_expiry_date: '2025-11-17'
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties: {}
                x-apidog-orders: []
              examples:
                '1':
                  summary: Создать платежа на выплату
                  value: |-
                    {
                        "success": true,
                        "data": {
                            "ps": "humo",
                            "store_id": 6,
                            "store_invoice_id": "112233",
                            "card_token": null,
                            "card_pan": "409784******3066",
                            "status": "draft",
                            "callback_url": null,
                            "phone": null,
                            "kyc_data": "{\"last_name\":\"BOLTAYEV\",\"first_name\":\"ALISHER\",\"middle_name\":\"MUMINOVICH\",\"passport\":\"AD21234567\",\"pinfl\":\"31105892514010\",\"dob\":\"1989-05-11\",\"passport_expiry_date\":\"2025-11-17\"}",
                            "terminal_id": "186368T6",
                            "merchant_id": "01200000119700D",
                            "commission_type": "up",
                            "total_amount": 10000,
                            "payment_amount": 10000,
                            "commission_amount": 0,
                            "id": 11351395,
                            "uuid": "a81b7b62-6d86-11f0-9a1b-00505680eaf6",
                            "multicard_user_id": null,
                            "ps_id": null,
                            "ps_uniq_id": null,
                            "ps_response_code": null,
                            "payment_time": null,
                            "added_on": "2025-07-31 01:49:16",
                            "store": {StoreModel},
                            "receipt_url": null,
                            "ps_response_msg": null
                        }
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
                      details: >-
                        Для пополнения на эту сумму необходимо пройти
                        идентификацию
          headers: {}
          x-apidog-name: Создать платежа на выплату
        '400':
          description: >-
            При попытке добавления карты с неправильным номером, система
            возвращает ошибку с кодом 400
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
          headers: {}
          x-apidog-name: Неверный номер карты для начисления платежа
        x-400:Создание платежа свыше 10 млн сум без полей kyc:
          description: >-
            При попытке создания платежа свыше 10 млн сум без идентификации,
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
          headers: {}
          x-apidog-name: Создание платежа свыше 10 млн сум без полей kyc
      security: []
      x-apidog-folder: Выплаты на карту (payouts)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729323-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B4%D0%B8%D1%82%D1%8C-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D1%83-19729324e0.md -->

# Подтвердить выплату

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/credit/{payment_uuid}:
    put:
      summary: Подтвердить выплату
      deprecated: false
      description: >-
        (!) При получении ошибки с кодом «ERROR_UNKNOWN» или таймаута на запрос,
        необходимо проверять статус транзакции до тех пор, пока не получен
        конечный (error или success).
      tags:
        - Выплаты на карту (payouts)
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
              required:
                - otp
              x-apidog-orders:
                - otp
            example:
              otp: '112233'
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
                    "data": {
                        "id": 11351395,
                        "uuid": "a81b7b62-6d86-11f0-9a1b-00505680eaf6",
                        "store_id": 6,
                        "multicard_user_id": null,
                        "ps": "humo",
                        "store_invoice_id": "112233",
                        "merchant_id": "01200000119700D",
                        "terminal_id": "186368T6",
                        "payment_amount": 10000,
                        "commission_amount": 0,
                        "commission_type": "up",
                        "total_amount": 10000,
                        "phone": null,
                        "card_token": null,
                        "card_pan": "409784******3066",
                        "ps_id": "5002050246",
                        "ps_uniq_id": "521120956011",
                        "status": "success",
                        "ps_response_code": null,
                        "payment_time": "2025-07-31 01:51:12",
                        "callback_url": null,
                        "kyc_data": "{\"last_name\":\"BOLTAYEV\",\"first_name\":\"ALISHER\",\"middle_name\":\"MUMINOVICH\",\"passport\":\"AD21234567\",\"pinfl\":\"31105892514010\",\"dob\":\"1989-05-11\",\"passport_expiry_date\":\"2025-11-17\"}",
                        "added_on": "2025-07-31 01:49:16",
                        "store": {StoreModel},
                        "receipt_url": "https://checkout.multicard.uz/credit/check/a81b7b62-6d86-11f0-9a1b-00505680eaf6",
                        "ps_response_msg": null
                    }
                }
          headers: {}
          x-apidog-name: Подтвердить выплату
      security: []
      x-apidog-folder: Выплаты на карту (payouts)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729324-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82%D0%B5-19729325e0.md -->

# Получение информации о выплате

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/credit/{payment_uuid}:
    get:
      summary: Получение информации о выплате
      deprecated: false
      description: ''
      tags:
        - Выплаты на карту (payouts)
      parameters:
        - name: payment_uuid
          in: path
          description: ''
          required: true
          example: 83e73262-b626-11f0-af7c-005056b4367d
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
                    "data": {
                        "id": 11351395,
                        "uuid": "a81b7b62-6d86-11f0-9a1b-00505680eaf6",
                        "store_id": 6,
                        "multicard_user_id": null,
                        "ps": "humo",
                        "store_invoice_id": "112233",
                        "merchant_id": "01200000119700D",
                        "terminal_id": "186368T6",
                        "payment_amount": 10000,
                        "commission_amount": 0,
                        "commission_type": "up",
                        "total_amount": 10000,
                        "phone": null,
                        "card_token": null,
                        "card_pan": "409784******3066",
                        "ps_id": "5002050246",
                        "ps_uniq_id": "521120956011",
                        "status": "success",
                        "ps_response_code": null,
                        "payment_time": "2025-07-31 01:51:12",
                        "callback_url": null,
                        "kyc_data": "{\"last_name\":\"BOLTAYEV\",\"first_name\":\"ALISHER\",\"middle_name\":\"MUMINOVICH\",\"passport\":\"AD21234567\",\"pinfl\":\"31105892514010\",\"dob\":\"1989-05-11\",\"passport_expiry_date\":\"2025-11-17\"}",
                        "added_on": "2025-07-31 01:49:16",
                        "store": {StoreModel},
                        "receipt_url": "https://checkout.multicard.uz/credit/check/a81b7b62-6d86-11f0-9a1b-00505680eaf6",
                        "ps_response_msg": null
                    }
                }
          headers: {}
          x-apidog-name: Model
      security: []
      x-apidog-folder: Выплаты на карту (payouts)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729325-run
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

