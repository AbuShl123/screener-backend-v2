# Multicard — Привязка карт (API) / Card binding via API (PCI DSS)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82-api-4488546f0.md -->

# Привязка карт (API)

> Данные методы доступны только для Партнеров, имеющих сертификат PCI DSS. Позволяют добавить карту Uzcard/Humo на странице Партнера.

Данные методы доступны только для Партнеров, имеющих сертификат PCI DSS. Позволяют добавить карту Uzcard/Humo на странице Партнера.


---

<!-- source: https://docs.multicard.uz/%D0%B4%D0%BE%D0%B1%D0%B0%D0%B2%D0%BB%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D1%81-%D0%BF%D0%BE%D0%BC%D0%BE%D1%89%D1%8C%D1%8E-%D0%BA%D0%B0%D1%80%D1%82%D0%BE%D1%87%D0%BD%D1%8B%D1%85-%D0%B4%D0%B0%D0%BD%D0%BD%D1%8B%D1%85-19729308e0.md -->

# Добавление карты с помощью карточных данных

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card:
    post:
      summary: Добавление карты с помощью карточных данных
      deprecated: false
      description: >-
        Метод отправляет SMS-код на номер телефон держателя карты. Отправленный
        код необходимо передать в методе подтверждения привязки.

        Метод доступен только для партнеров, которых имеется сертификат PCI DSS.
      tags:
        - Привязка карт (API)
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                pan:
                  type: string
                  description: Номер карты (Uzcard или Humo)
                expiry:
                  type: string
                  description: Срок действия карты (yymm)
                user_phone:
                  type: string
                  description: >-
                    Номер телефона пользователя в формате 998XXXXXXXXX.
                    Обязателен для карт Humo. Если передан, то будет проверено
                    соответствие с номер смс-информаирования на карте (для
                    Uzcard/Humo)
                cvc:
                  type: string
                holder_name:
                  type: string
                session_id:
                  type: string
              required:
                - pan
                - expiry
                - user_phone
              x-apidog-orders:
                - pan
                - expiry
                - user_phone
                - cvc
                - holder_name
                - session_id
              x-apidog-ignore-properties: []
            example:
              pan: '8600303655375959'
              expiry: '2603'
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
                    type: object
                    properties:
                      id:
                        type: integer
                      application_id:
                        type: integer
                        description: >-
                          Идентификатор приложения, выданный со стороны
                          Multicard
                      payer_id:
                        type: 'null'
                        description: >-
                          Значение поля session_id из ответа на запрос привязки
                          карты
                      card_pan:
                        type: string
                        description: |-
                          Max length:16
                          Маскированный номер карты
                      card_token:
                        type: string
                        description: |-
                          Max length:255
                          Токен карты для проведения платежных операций с картой
                      phone:
                        type: 'null'
                        description: >-
                          Max length:12

                          Телефон держателя карты в формате 998XXXXXXXXX
                          (поддерживается только для карт Uzcard и Humo)
                      holder_name:
                        type: 'null'
                        description: Имя и фамилия владельца карты
                      pinfl:
                        type: 'null'
                        description: >-
                          ПИНФЛ держателя карты (возвращается в случае передачи
                          в запросе на создание привязки для карт Uzcard и Humo)

                          Max length:14
                      ps:
                        type: string
                        description: |-
                          Allowed values (5)
                          uzcard,
                          humo,
                          visa,
                          mastercard,
                          unionpay
                          hide all
                          Платежная система
                      status:
                        $ref: '#/components/schemas/PaymentStatusEnum'
                      added_on:
                        type: string
                        description: Время добавления карты в формате YYYY-mm-dd H:i:s
                      card_status:
                        type: 'null'
                      sms_inform:
                        type: 'null'
                    required:
                      - id
                      - application_id
                      - payer_id
                      - card_pan
                      - card_token
                      - phone
                      - holder_name
                      - pinfl
                      - ps
                      - status
                      - added_on
                      - card_status
                      - sms_inform
                    x-apidog-orders:
                      - id
                      - application_id
                      - payer_id
                      - card_pan
                      - card_token
                      - phone
                      - holder_name
                      - pinfl
                      - ps
                      - status
                      - added_on
                      - card_status
                      - sms_inform
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: Успешно
        '400':
          description: >-
            При попытке добавления карты с не подключенным SMS информированием,
            система возвращает ошибку с кодом 400, и сообщает клиенту об ошибке
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: 'Статус запроса '
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
          x-apidog-name: Не подключено SMS-информирование
        '429':
          description: >-
            При повторной отправки запроса на SMS пароль, система возвращает
            ошибку с кодом 400, и клиент получает сообщение об ошибке
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: 'Статус запроса '
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
          x-apidog-name: SMS уже отправлено
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
                    description: 'Статус запроса '
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
        x-400:Запрос с неподдерживаемой картой:
          description: >-
            При попытке добавления неподдерживаемой карты , система возвращает
            ошибку с кодом 400, и сообщает клиенту об ошибке
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    description: 'Статус запроса '
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
          x-apidog-name: Запрос с неподдерживаемой картой
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
      x-apidog-folder: Привязка карт (API)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729308-run
components:
  schemas:
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%B4%D1%82%D0%B2%D0%B5%D1%80%D0%B6%D0%B4%D0%B5%D0%BD%D0%B8%D0%B5-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-19729309e0.md -->

# Подтверждение привязки

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/{card_token}:
    put:
      summary: Подтверждение привязки
      deprecated: false
      description: ''
      tags:
        - Привязка карт (API)
      parameters:
        - name: card_token
          in: path
          description: Идентификатор добавляемой карты в систему
          required: true
          example: 6225f3c93f7a880142782fa4
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
                pinfl:
                  type: string
                  description: >-
                    ПИНФЛ клиента. Если передан, то будет осуществлена проверка
                    на принадлежность карты к данному лицу (только для
                    Uzcard/Humo)
                  nullable: true
              required:
                - otp
              x-apidog-orders:
                - otp
                - pinfl
              x-apidog-ignore-properties: []
            example:
              otp: '112233'
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
                    type: object
                    properties:
                      id:
                        type: integer
                      application_id:
                        type: integer
                        description: >-
                          Идентификатор приложения, выданный со стороны
                          Multicard
                      payer_id:
                        type: string
                        description: >-
                          Значение поля session_id из ответа на запрос привязки
                          карты
                      card_pan:
                        type: string
                        description: |-
                          Max length:16
                          Маскированный номер карты
                      card_token:
                        type: string
                        description: |-
                          Max length:255
                          Токен карты для проведения платежных операций с картой
                      phone:
                        type: string
                        description: >-
                          Телефон плательщика в формате 998XXXXXXXXX (при
                          наличии)
                      holder_name:
                        type: string
                        description: Имя и фамилия владельца карты
                      pinfl:
                        type: 'null'
                        description: >-
                          ПИНФЛ держателя карты (возвращается в случае передачи
                          в запросе на создание привязки для карт Uzcard и Humo)

                          Max length:14
                      ps:
                        type: string
                        description: |-
                          Allowed values (5)
                          uzcard,
                          humo,
                          visa,
                          mastercard,
                          unionpay
                          hide all
                          Платежная система
                      status:
                        $ref: '#/components/schemas/PaymentStatusEnum'
                      added_on:
                        type: string
                        description: Время добавления карты в формате YYYY-mm-dd H:i:s
                      card_status:
                        type: object
                        properties:
                          code:
                            type: integer
                          description:
                            type: string
                        required:
                          - code
                          - description
                        x-apidog-orders:
                          - code
                          - description
                        x-apidog-ignore-properties: []
                      sms_inform:
                        type: boolean
                    required:
                      - id
                      - application_id
                      - payer_id
                      - card_pan
                      - card_token
                      - phone
                      - holder_name
                      - pinfl
                      - ps
                      - status
                      - added_on
                      - card_status
                      - sms_inform
                    x-apidog-orders:
                      - id
                      - application_id
                      - payer_id
                      - card_pan
                      - card_token
                      - phone
                      - holder_name
                      - pinfl
                      - ps
                      - status
                      - added_on
                      - card_status
                      - sms_inform
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
                x-apidog-ignore-properties: []
              examples:
                '1':
                  summary: Привязка карты
                  value:
                    success: true
                    data:
                      id: 55
                      application_id: 1
                      payer_id: '2313122'
                      card_pan: 409784******3066
                      card_token: 6225f3c93f7a110142782fa4
                      phone: '998901234567'
                      holder_name: AAAA BBBBB
                      pinfl: null
                      ps: uzcard
                      status: active
                      added_on: '2022-04-21 14:36:29'
                      card_status:
                        code: 0
                        description: Active
                      sms_inform: true
                '2':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_WRONG_OTP
                      details: Код подтверждения истек, запросите новый
                '3':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_WRONG_OTP
                      details: Неверный код подтверждения
                '4':
                  summary: Example 1
                  value:
                    success: false
                    error:
                      code: ERROR_FIELDS
                      details: Не передан SMS-код
          headers: {}
          x-apidog-name: Привязка карты
        '400':
          description: >-
            При вводе некорректного код пароля, система возвращает ошибку с
            кодом 400, и сообщает клиенту об ошибке
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
          x-apidog-name: Отправка неправильного OTP
        '423':
          description: >-
            При вводе истёкшого код пароля, система возвращает ошибку с кодом
            400, и сообщает клиенту об ошибке
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
          x-apidog-name: Истёк код пароля
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
      x-apidog-folder: Привязка карт (API)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729309-run
components:
  schemas:
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

<!-- source: https://docs.multicard.uz/%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BF%D0%BE-%D0%B5%D0%B5-%D0%BD%D0%BE%D0%BC%D0%B5%D1%80%D1%83-22557089e0.md -->

# Проверка карты по ее номеру

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/check/{pan}:
    get:
      summary: Проверка карты по ее номеру
      deprecated: false
      description: ''
      tags:
        - Привязка карт (API)
      parameters:
        - name: pan
          in: path
          description: ''
          required: true
          example: '8600303655375959'
          schema:
            type: string
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
                    type: object
                    properties:
                      masked_pan:
                        type: string
                        description: Маскированный PAN
                      bank:
                        type: object
                        properties:
                          bin:
                            type: string
                            description: BIN
                          name:
                            type: string
                            description: Наименование банка
                          color:
                            type: string
                            description: HEX-код цвета фона для логотипа
                            nullable: true
                          logo:
                            type: string
                            description: URL на логотип
                            nullable: true
                          ps_bank_id:
                            type: string
                            description: Код банка
                            nullable: true
                          country:
                            type: string
                            description: Страна
                            nullable: true
                        x-apidog-orders:
                          - bin
                          - name
                          - color
                          - logo
                          - ps_bank_id
                          - country
                        required:
                          - bin
                          - name
                          - color
                          - logo
                          - ps_bank_id
                          - country
                      ps:
                        type: string
                        enum:
                          - uzcard
                          - humo
                          - visa
                          - mastercard
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
                        description: Платежная система
                      holder_name:
                        type: string
                        description: Имя держателя
                        nullable: true
                    required:
                      - ps
                      - holder_name
                      - masked_pan
                      - bank
                    x-apidog-orders:
                      - ps
                      - holder_name
                      - masked_pan
                      - bank
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data:
                  ps: humo
                  holder_name: ILXOM IDIYEV
                  masked_pan: 986027******1519
                  bank:
                    bin: '986027'
                    name: Orient Finance Bank
                    color: D1AA3B
                    logo: https://cdn.multicard.uz/banks/ofb.png
                    ps_bank_id: '27'
                    country: '860'
          headers: {}
          x-apidog-name: Привязка карты
        '400':
          description: >-
            При поиске карты с недействительным номером, возвращается ошибка с
            кодом 400
          content:
            application/json:
              schema:
                title: ''
                type: object
                properties:
                  success:
                    type: boolean
                  error:
                    type: object
                    properties:
                      code:
                        type: string
                      details:
                        type: string
                    x-apidog-orders:
                      - code
                      - details
                    required:
                      - code
                      - details
                x-apidog-orders:
                  - success
                  - error
                required:
                  - success
                  - error
              example:
                success: false
                error:
                  code: ERROR_CARD_NOT_FOUND
                  details: Неверный номер карты или срок действия
          headers: {}
          x-apidog-name: Неверный номер карты
      security: []
      x-apidog-folder: Привязка карт (API)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-22557089-run
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

