# Multicard — Холдирование / Holds (authorize & capture)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-4488548f0.md -->

# Холдирование

> Процесс проведения платежа с использованием холдирования происходит в 3 этапа:

1. Создание холда с помощью метода POST /payment/hold. Клиенту отправляется SMS-код (OTP). Необходимо сохранить uuid транзакции из поля payment.uuid.
    
2. Подтверждение холда с помощью метода PUT /payment/hold/{payment.uuid} или путем перенаправления пользователя по ссылке в поле payment.checkout_url. После успешного подтверждения на карте клиента холдируется (блокируется) указанная сумма. Если у Партнера включено безакцептное списание и карта ранее была привязан, то необходимо отправить пустое тело запроса (без SMS-кода).
    
3. Списание активного холда с помощью методов PUT /payment/hold/{payment.uuid}/charge или либо его отмена: DELETE /payment/hold/{payment.uuid}.

Процесс проведения платежа с использованием холдирования происходит в 3 этапа:

1. Создание холда с помощью метода POST /payment/hold. Клиенту отправляется SMS-код (OTP). Необходимо сохранить uuid транзакции из поля payment.uuid.
    
2. Подтверждение холда с помощью метода PUT /payment/hold/{payment.uuid} или путем перенаправления пользователя по ссылке в поле payment.checkout_url. После успешного подтверждения на карте клиента холдируется (блокируется) указанная сумма. Если у Партнера включено безакцептное списание и карта ранее была привязан, то необходимо отправить пустое тело запроса (без SMS-кода).
    
3. Списание активного холда с помощью методов PUT /payment/hold/{payment.uuid}/charge или либо его отмена: DELETE /payment/hold/{payment.uuid}.


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D1%82%D1%8C-%D0%B7%D0%B0%D1%8F%D0%B2%D0%BA%D1%83-%D0%BD%D0%B0-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-19729318e0.md -->

# Создать заявку на холдирование

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/hold:
    post:
      summary: Создать заявку на холдирование
      deprecated: false
      description: ''
      tags:
        - Холдирование
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
                  description: |+
                    Сумма платежа в тийинах

                store_id:
                  type: integer
                  description: >+
                    ID (int) или UUID (string) кассы, выданный со стороны
                    Multicard

                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                expiry:
                  type: integer
                  description: >-
                    Minimum:1

                    Maximum:43200

                    Срок действия холдирования в минутах. После этого времени
                    средства будут возвращены на карту. Допустимо не более 30
                    дней (43200)
                split:
                  $ref: '#/components/schemas/splitRequest'
              required:
                - card
                - amount
                - store_id
                - invoice_id
                - expiry
              x-apidog-orders:
                - card
                - amount
                - store_id
                - invoice_id
                - expiry
                - split
              x-apidog-ignore-properties: []
            example:
              card:
                token: 6225f3c93f7a880142782fa4
              amount: 10000000
              store_id: 15
              invoice_id: '18177'
              expiry: 43200
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
                    "data": {
                        "id": 753967,
                        "status": "draft",
                        "expiry": "2025-03-08 23:12:15",
                        "added_on": "2025-03-07 23:12:15",
                        "updated_on": null,
                        "payment": {PaymentModel}
                    }
                }
          headers: {}
          x-apidog-name: HoldModel
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
              examples:
                '2':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_FIELDS
                      details: Необходимо заполнить «Amount».
                '3':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_FIELDS
                      details: Необходимо заполнить «Amount».
          headers: {}
          x-apidog-name: Ошибочная валидация
        'x-400:Запрос с некорректным номером карты или срока действия ':
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
          x-apidog-name: 'Запрос с некорректным номером карты или срока действия '
      security: []
      x-apidog-folder: Холдирование
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729318-run
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
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B4%D0%B8%D1%82%D1%8C-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B1%D0%BB%D0%BE%D0%BA%D0%B8%D1%80%D0%BE%D0%B2%D0%BA%D0%B0-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D0%B5-19729319e0.md -->

# Подтвердить холдирование (блокировка средств на карте)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/hold/{payment_uuid}:
    put:
      summary: Подтвердить холдирование (блокировка средств на карте)
      deprecated: false
      description: ''
      tags:
        - Холдирование
      parameters:
        - name: payment_uuid
          in: path
          description: Идентификатор созданного платежа
          required: true
          example: 9ef1aa95-b8b2-11f0-af7c-005056b4367d
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
              otp: '1122'
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
                        "id": 753967,
                        "status": "active",
                        "expiry": "2025-03-08 23:12:15",
                        "added_on": "2025-03-07 23:12:15",
                        "updated_on": null,
                        "payment": {PaymentModel}
                    }
                }
          headers: {}
          x-apidog-name: HoldModel Copy
      security: []
      x-apidog-folder: Холдирование
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729319-run
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

<!-- source: https://docs.multicard.uz/%D1%81%D0%BF%D0%B8%D1%81%D0%B0%D1%82%D1%8C-%D0%B7%D0%B0%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%BD%D1%8B%D0%B5-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2%D0%B0-19729320e0.md -->

# Списать захолдированные средства

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/hold/{payment_uuid}/charge:
    put:
      summary: Списать захолдированные средства
      deprecated: false
      description: >-
        Метод осуществляет финальное списание захолдированных средств. Допустимо
        передать в теле сумму списания (должна быть меньше, чем сумма
        холдирования), иначе будет списана вся захолдированная сумма.


        Также в запросе можно передать массивы split и ofd.


        В случае получения ошибки с кодом **ERROR_DEBIT_UNKNOWN** или
        **ERROR_CALLBACK_TIMEOUT**, необходимо проверять статус платежа пока не
        будет получен конечный статус (success или error)
      tags:
        - Холдирование
      parameters:
        - name: payment_uuid
          in: path
          description: Идентификатор созданного платежа
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
                amount:
                  type: integer
                  description: |+
                    Сумма перевода в тийинах


              required:
                - amount
              x-apidog-orders:
                - amount
            example:
              amount: 100000
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
          x-apidog-name: PaymentModel Copy
      security: []
      x-apidog-folder: Холдирование
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729320-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B8-19729321e0.md -->

# Получение информации о холдировании

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/hold/{payment_uuid}:
    get:
      summary: Получение информации о холдировании
      deprecated: false
      description: ''
      tags:
        - Холдирование
      parameters:
        - name: payment_uuid
          in: path
          description: Идентификатор созданного платежа
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
                        "id": 753967,
                        "status": "draft",
                        "expiry": "2025-03-08 23:12:15",
                        "added_on": "2025-03-07 23:12:15",
                        "updated_on": null,
                        "payment": {PaymentModel}
                    }
                }
          headers: {}
          x-apidog-name: HoldModel Copy
      security: []
      x-apidog-folder: Холдирование
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729321-run
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

<!-- source: https://docs.multicard.uz/%D0%BE%D1%82%D0%BC%D0%B5%D0%BD%D0%B0-%D0%B7%D0%B0%D1%85%D0%BE%D0%BB%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-%D1%81%D1%80%D0%B5%D0%B4%D1%81%D1%82%D0%B2-%D0%B4%D0%BE-%D1%81%D0%BF%D0%B8%D1%81%D0%B0%D0%BD%D0%B8%D1%8F-19729322e0.md -->

# Отмена захолдированных средств (до списания)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/hold/{payment_uuid}:
    delete:
      summary: Отмена захолдированных средств (до списания)
      deprecated: false
      description: >-
        Метод позволяет вернуть (разблокировать) захолдированные средства на
        карту клиента до срока истечения холдирования
      tags:
        - Холдирование
      parameters:
        - name: payment_uuid
          in: path
          description: Идентификатор созданного платежа
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
                        "id": 753967,
                        "status": "canceled",
                        "expiry": "2025-03-08 23:12:15",
                        "added_on": "2025-03-07 23:12:15",
                        "updated_on": null,
                        "payment": {PaymentModel}
                    }
                }
          headers: {}
          x-apidog-name: HoldModel Copy 2
        '403':
          description: >-
            При попытке отмены неактивного холда, и завершенную оплатой,
            возвращается ошибка 400
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
                  code: ERROR_PAYMENT_APPLIED
                  details: Холд не активен
          headers: {}
          x-apidog-name: Отмена холдирования после списания
      security: []
      x-apidog-folder: Холдирование
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729322-run
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

