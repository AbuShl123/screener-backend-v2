# Multicard — Дополнительные методы (Additional methods)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D1%8F-%D0%BE-%D0%BF%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B8-19729326e0.md -->

# Информация о приложении

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/application:
    get:
      summary: Информация о приложении
      deprecated: false
      description: ''
      tags:
        - Дополнительные методы
      parameters: []
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
                        type: string
                        description: >-
                          Идентификатор приложения, выданный со стороны
                          Multicard
                      wallet_sum:
                        type: integer
                        description: Баланс депозита (кошелька) в тийинах
                      wallet_sender_account:
                        type: 'null'
                      wallet_overdraft:
                        type: integer
                        description: Сумма ухода "в минус" (в тийинах)
                      wallet_contract_num:
                        type: 'null'
                      otp_required:
                        type: integer
                      otp_gateway:
                        type: 'null'
                      sms_nickname:
                        type: 'null'
                      allow_bank_transaction:
                        type: integer
                      otp_length:
                        type: integer
                        description: Длина одноразового пароля (OTP)
                      official_name:
                        type: string
                      offer_url:
                        type: 'null'
                      phone:
                        type: 'null'
                      added_on:
                        type: string
                      updated_on:
                        type: string
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
                      - allow_bank_transaction
                      - otp_length
                      - official_name
                      - offer_url
                      - phone
                      - added_on
                      - updated_on
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
                      - allow_bank_transaction
                      - otp_length
                      - official_name
                      - offer_url
                      - phone
                      - added_on
                      - updated_on
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
          headers: {}
          x-apidog-name: Информация о приложении
      security: []
      x-apidog-folder: Дополнительные методы
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729326-run
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

<!-- source: https://docs.multicard.uz/%D1%80%D0%B5%D0%BA%D0%B2%D0%B8%D0%B7%D0%B8%D1%82%D1%8B-%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B0%D1%82%D0%B5%D0%BB%D1%8F-19729327e0.md -->

# Реквизиты получателя

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/merchant-account/{recipient}:
    get:
      summary: Реквизиты получателя
      deprecated: false
      description: Получить информацию о банковских реквизитах
      tags:
        - Дополнительные методы
      parameters:
        - name: recipient
          in: path
          description: ''
          required: true
          example: 5378f655-ae41-11ee-97a8-005056b4367d
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
                      id:
                        type: integer
                      uuid:
                        type: string
                        description: Уникальный идентификатор транзакции в Multicard
                      merchant_id:
                        type: integer
                        description: Идентификатор мерчанта ePOS-терминала
                      tin:
                        type: string
                        description: ИНН компании
                      official_name:
                        type: string
                        description: Официальное юридическое наименование организации
                      mfo:
                        type: string
                        description: >-
                          Банковский идентификационный код (МФО), который
                          указывает на конкретное финансовое учреждение
                      account_no:
                        type: string
                        description: >-
                          Расчётный счёт организации в банке (р/с), используемый
                          для проведения платежей и расчётов
                      address:
                        type: string
                        description: >-
                          Юридический адрес организации (город, район, улица,
                          дом и т.д.)
                      director:
                        type: string
                        description: Руководитель организации (ФИО директора)
                      director_pinfl:
                        type: string
                        description: ПИНФЛ директора
                      vat_payer:
                        type: boolean
                        description: >-
                          Признак плательщика НДС (если true — организация
                          зарегистрирована как плательщик налога на добавленную
                          стоимость)
                      is_commitent:
                        type: boolean
                        description: >-
                          Признак того, что организация выступает комитентом
                          (если true — организация работает по договору
                          комиссии)
                      contract_no:
                        type: 'null'
                        description: Номер договора
                      contract_date:
                        type: 'null'
                        description: Дата заключения договора
                      active:
                        type: integer
                        description: Статус договора, если 1 - актив.
                      tax_contract_begin:
                        type: string
                        description: Дата начала действия договора с налоговыми органами
                      tax_contract_end:
                        type: string
                        description: Дата окончания действия договора с налоговыми органами
                    required:
                      - id
                      - uuid
                      - merchant_id
                      - tin
                      - official_name
                      - mfo
                      - account_no
                      - address
                      - director
                      - director_pinfl
                      - vat_payer
                      - is_commitent
                      - contract_no
                      - contract_date
                      - active
                      - tax_contract_begin
                      - tax_contract_end
                    x-apidog-orders:
                      - id
                      - uuid
                      - merchant_id
                      - tin
                      - official_name
                      - mfo
                      - account_no
                      - address
                      - director
                      - director_pinfl
                      - vat_payer
                      - is_commitent
                      - contract_no
                      - contract_date
                      - active
                      - tax_contract_begin
                      - tax_contract_end
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data:
                  id: 174
                  uuid: 540311d7-4e1d-11ee-8bac-00505680eaf6
                  merchant_id: 13
                  tin: '307578794'
                  official_name: '"MULTICARD PAYMENT" AJ'
                  mfo: '00491'
                  account_no: '20208000905255407001'
                  address: >-
                    г. Ташкент, Яшнабадский район, ул. Содик Азимов, Туйтепа
                    МСГ, 50- Дом, -  
                  director: ZAKIROV RUSTAM RINATOVICH
                  director_pinfl: '32307890270337'
                  vat_payer: true
                  is_commitent: true
                  contract_no: null
                  contract_date: null
                  active: 1
                  tax_contract_begin: '2024-09-20'
                  tax_contract_end: '2050-01-01'
          headers: {}
          x-apidog-name: Реквизиты получателя
      security: []
      x-apidog-folder: Дополнительные методы
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729327-run
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

<!-- source: https://docs.multicard.uz/%D1%80%D0%B5%D0%B5%D1%81%D1%82%D1%80-%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D0%B4%D0%B5%D0%BD%D0%BD%D1%8B%D1%85-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%B5%D0%B9-19729328e0.md -->

# Реестр проведенных платежей

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/store/{store_id}/history:
    get:
      summary: Реестр проведенных платежей
      deprecated: false
      description: ''
      tags:
        - Дополнительные методы
      parameters:
        - name: store_id
          in: path
          description: ''
          required: true
          example: '6'
          schema:
            type: string
        - name: offset
          in: query
          description: Отступ записей в ответе
          required: true
          example: 0
          schema:
            type: integer
        - name: limit
          in: query
          description: Кол-во записей в ответе
          required: true
          example: 100
          schema:
            type: integer
        - name: only_status
          in: query
          description: фильтрация транзакций с конкретным статусом
          required: false
          example: success
          schema:
            type: string
            enum:
              - success
              - error
              - revert
              - progress
              - billing
              - draft
            x-apidog-enum:
              - value: success
                name: ''
                description: ''
              - value: error
                name: ''
                description: ''
              - value: revert
                name: ''
                description: ''
              - value: progress
                name: ''
                description: ''
              - value: billing
                name: ''
                description: ''
              - value: draft
                name: ''
                description: ''
        - name: start_date
          in: query
          description: Время в формате YYYY-mm-dd H:i:s. Временная зона GMT+5
          required: true
          example: '2025-03-01 00:00:00'
          schema:
            type: string
        - name: end_date
          in: query
          description: Время в формате YYYY-mm-dd H:i:s. Временная зона GMT+5
          required: true
          example: '2025-03-02 00:00:00'
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
                      pagination:
                        type: object
                        properties:
                          total:
                            type: integer
                          offset:
                            type: integer
                          limit:
                            type: integer
                        x-apidog-orders:
                          - total
                          - offset
                          - limit
                        required:
                          - total
                          - offset
                          - limit
                        x-apidog-ignore-properties: []
                      stat:
                        type: array
                        items:
                          type: object
                          properties:
                            status: &ref_0
                              description: Статус
                              $ref: '#/components/schemas/PaymentStatusEnum'
                            ps: &ref_1
                              description: Платежный метод (система или сервис)
                              $ref: '#/components/schemas/PaymentServiceEnum'
                            payment_amount:
                              type: integer
                              description: >-
                                сумма платежей по заданному платежному методу в
                                указанном статусе в тийинах
                              nullable: true
                          x-apidog-orders:
                            - status
                            - ps
                            - payment_amount
                          required:
                            - status
                            - ps
                            - payment_amount
                          x-apidog-ignore-properties: []
                        description: Агрегированная информация по транзакциям
                      list:
                        type: array
                        items:
                          type: object
                          properties:
                            id:
                              type: integer
                            uuid:
                              type: string
                              format: uuid
                            status: *ref_0
                            ps: *ref_1
                            store_invoice_id:
                              type: string
                            billing_id:
                              type: string
                            payment_time:
                              type: string
                            payment_amount:
                              type: integer
                            commission_amount:
                              type: integer
                            card_token:
                              type: string
                            card_pan:
                              type: string
                            ps_uniq_id:
                              type: string
                            refund_time:
                              type: string
                              format: date-time
                            added_on:
                              type: string
                              format: date-time
                          x-apidog-orders:
                            - id
                            - uuid
                            - status
                            - ps
                            - store_invoice_id
                            - billing_id
                            - payment_time
                            - payment_amount
                            - commission_amount
                            - card_token
                            - card_pan
                            - ps_uniq_id
                            - refund_time
                            - added_on
                          required:
                            - id
                            - uuid
                            - status
                            - ps
                            - store_invoice_id
                            - payment_amount
                            - commission_amount
                            - card_token
                            - added_on
                          x-apidog-ignore-properties: []
                        description: Список транзакций
                    x-apidog-orders:
                      - pagination
                      - stat
                      - list
                    required:
                      - pagination
                      - stat
                      - list
                    x-apidog-ignore-properties: []
                x-apidog-orders:
                  - success
                  - data
                required:
                  - success
                  - data
                x-apidog-ignore-properties: []
          headers: {}
          x-apidog-name: Success
        '400':
          description: ''
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
                    x-apidog-ignore-properties: []
                x-apidog-orders:
                  - success
                  - error
                required:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_FIELDS
                  details: Поле store_id является обязательным
          headers: {}
          x-apidog-name: Validation Error
        '403':
          description: ''
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
                    x-apidog-ignore-properties: []
                x-apidog-orders:
                  - success
                  - error
                required:
                  - success
                  - error
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_ACCESS_DENIED
                  details: Доступ запрещен
          headers: {}
          x-apidog-name: Access Denied Error
      security: []
      x-apidog-folder: Дополнительные методы
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729328-run
components:
  schemas:
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

<!-- source: https://docs.multicard.uz/%D0%B8%D1%81%D1%82%D0%BE%D1%80%D0%B8%D1%8F-%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D0%B4%D0%B5%D0%BD%D0%BD%D1%8B%D1%85-%D0%B2%D1%8B%D0%BF%D0%BB%D0%B0%D1%82-%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BF%D0%BE%D0%BF%D0%BE%D0%BB%D0%BD%D0%B5%D0%BD%D0%B8%D0%B9-19729329e0.md -->

# История проведенных выплат на карты (пополнений)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/store/{store_id}/credit-history:
    get:
      summary: История проведенных выплат на карты (пополнений)
      deprecated: false
      description: ''
      tags:
        - Дополнительные методы
      parameters:
        - name: store_id
          in: path
          description: ''
          required: true
          example: '6'
          schema:
            type: string
        - name: offset
          in: query
          description: Отступ записей в ответе
          required: true
          example: 0
          schema:
            type: integer
        - name: limit
          in: query
          description: Кол-во записей в ответе
          required: true
          example: 10
          schema:
            type: integer
        - name: only_status
          in: query
          description: фильтрация транзакций с конкретным статусом
          required: true
          example: success
          schema:
            type: string
        - name: start_date
          in: query
          description: Время в формате YYYY-mm-dd H:i:s. Временная зона GMT+5
          required: true
          example: '2025-03-01 00:00:00'
          schema:
            type: string
        - name: end_date
          in: query
          description: Время в формате YYYY-mm-dd H:i:s. Временная зона GMT+5
          required: true
          example: '2025-03-02 00:00:00'
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
                      pagination:
                        type: object
                        properties:
                          total:
                            type: integer
                          offset:
                            type: integer
                          limit:
                            type: integer
                        x-apidog-orders:
                          - total
                          - offset
                          - limit
                        required:
                          - total
                          - offset
                          - limit
                        x-apidog-ignore-properties: []
                      stat:
                        type: array
                        items:
                          type: object
                          properties:
                            status: &ref_0
                              description: Статус
                              $ref: '#/components/schemas/PaymentStatusEnum'
                            ps: &ref_1
                              description: Платежный метод (система или сервис)
                              $ref: '#/components/schemas/PaymentServiceEnum'
                            payment_amount:
                              type: integer
                              description: >-
                                сумма платежей по заданному платежному методу в
                                указанном статусе в тийинах
                              nullable: true
                          x-apidog-orders:
                            - status
                            - ps
                            - payment_amount
                          required:
                            - status
                            - ps
                            - payment_amount
                          x-apidog-ignore-properties: []
                        description: Агрегированная информация по транзакциям
                      list:
                        type: array
                        items:
                          type: object
                          properties:
                            id:
                              type: integer
                            uuid:
                              type: string
                              format: uuid
                            status: *ref_0
                            ps: *ref_1
                            store_invoice_id:
                              type: string
                            billing_id:
                              type: string
                            payment_time:
                              type: string
                            payment_amount:
                              type: integer
                            commission_amount:
                              type: integer
                            card_token:
                              type: string
                            card_pan:
                              type: string
                            ps_uniq_id:
                              type: string
                            added_on:
                              type: string
                              format: date-time
                          x-apidog-orders:
                            - id
                            - uuid
                            - status
                            - ps
                            - store_invoice_id
                            - billing_id
                            - payment_time
                            - payment_amount
                            - commission_amount
                            - card_token
                            - card_pan
                            - ps_uniq_id
                            - added_on
                          required:
                            - id
                            - uuid
                            - status
                            - ps
                            - store_invoice_id
                            - payment_amount
                            - commission_amount
                            - card_token
                            - added_on
                          x-apidog-ignore-properties: []
                        description: Список транзакций
                    x-apidog-orders:
                      - pagination
                      - stat
                      - list
                    required:
                      - pagination
                      - stat
                      - list
                    x-apidog-ignore-properties: []
                x-apidog-orders:
                  - success
                  - data
                required:
                  - success
                  - data
                x-apidog-ignore-properties: []
              example:
                success: true
                data:
                  pagination:
                    total: 9
                    offset: 0
                    limit: 10
                  stat:
                    - status: draft
                      ps: uzcard
                      payment_amount: '300000'
                    - status: success
                      ps: uzcard
                      payment_amount: '50000'
                    - status: error
                      ps: uzcard
                      payment_amount: '100000'
                  list:
                    - id: 25788622
                      uuid: fc207b3d-79b8-11ef-bfaa-00505680eaf6
                      status: draft
                      ps: uzcard
                      store_invoice_id: '909903929'
                      billing_id: null
                      payment_time: null
                      payment_amount: 50000
                      commission_amount: 1250
                      total_amount: 50000
                      card_token: 65b81d37b53977001c14391a
                      card_pan: 860006******2278
                      added_on: '2024-09-23 19:34:48'
          headers: {}
          x-apidog-name: Success
      security: []
      x-apidog-folder: Дополнительные методы
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729329-run
components:
  schemas:
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

