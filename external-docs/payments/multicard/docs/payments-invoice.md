# Multicard — Оплата на платежной странице (Invoice / Checkout)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-%D0%BD%D0%B0-%D0%BF%D0%BB%D0%B0%D1%82%D0%B5%D0%B6%D0%BD%D0%BE%D0%B9-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B5-multicard-4488544f0.md -->

# Оплата на платежной странице Multicard

> Простая оплата путем создания инвойса и перенаправления по ссылке для его оплаты. Оплата проводится в соответствии со следующими этапами:

1. Система Партнера [создает инвойс](#852ea03f-0391-4855-9e10-0827d515520b), в ответе получает ссылку на страницу оплаты.
    
2. Пользователь проводит оплату путем добавления карты, либо через платежные приложения (Payme, Click, Uzumbank, Anorbank, Oson, Alif, Xazna, Beepul, Trastpay).
    
3. В случае успешного платежа, платежный шлюз Multicard отправляет [callback-запрос](#89acd801-f0ff-4794-bc71-accf597deb4f) в систему Партнера. Можно также настроить [отправку webhooks](#0c181fd2-2239-44c6-8955-278bc4cb3c93) при каждом изменении статуса транзакции.

Простая оплата путем создания инвойса и перенаправления по ссылке для его оплаты. Оплата проводится в соответствии со следующими этапами:

1. Система Партнера [создает инвойс](#852ea03f-0391-4855-9e10-0827d515520b), в ответе получает ссылку на страницу оплаты.
    
2. Пользователь проводит оплату путем добавления карты, либо через платежные приложения (Payme, Click, Uzumbank, Anorbank, Oson, Alif, Xazna, Beepul, Trastpay).
    
3. В случае успешного платежа, платежный шлюз Multicard отправляет [callback-запрос](#89acd801-f0ff-4794-bc71-accf597deb4f) в систему Партнера. Можно также настроить [отправку webhooks](#0c181fd2-2239-44c6-8955-278bc4cb3c93) при каждом изменении статуса транзакции.


---

<!-- source: https://docs.multicard.uz/%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B0-19729296e0.md -->

# Создание инвойса

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/invoice:
    post:
      summary: Создание инвойса
      deprecated: false
      description: >-
        Метод создает инвойс и возвращает ссылку на его оплату (в поле
        checkout_url). Система мерчанта должна перенаправить клиента на
        указанный URL.


        В случае, если система Партнера формирует QR-код для оплаты, то можно
        использовать короткую ссылку из поля short_link (возвращается только в
        продакшн-среде).


        Система партнера должна сохранить идентификатор транзакции из поля uuid.


        Если передано поле callback_url, при успешной оплате в систему Партнера
        будет отправлен коллбэк-запрос.
      tags:
        - Оплата на платежной странице Multicard
      parameters:
        - name: X-Access-Token
          in: header
          description: ''
          required: false
          example: '{{multicard_token}}'
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                store_id:
                  type: string
                  description: >-
                    ID (int) или UUID (string) кассы, выданный со стороны
                    Multicard
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                invoice_id:
                  type: string
                  description: >-
                    Любой идентификатор платежа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                lang:
                  type: string
                  description: Язык страницы чекаута
                  enum:
                    - ru
                    - uz
                    - en
                  x-apidog-enum:
                    - value: ru
                      name: ''
                      description: ''
                    - value: uz
                      name: ''
                      description: ''
                    - value: en
                      name: ''
                      description: ''
                return_url:
                  type: string
                  description: URL для перенаправления пользователя после оплаты
                return_error_url:
                  type: string
                  description: >-
                    URL для перенаправления пользователя после неуспешной
                    оплаты. Если не передан, то используется return_url
                callback_url:
                  type: string
                  description: URL для отправки callback-запроса
                sms:
                  type: string
                  description: >-
                    Если передано, то по указанному номер будет отправлена
                    ссылка на инвойс. Формат 998XXXXXXXXX
                ttl:
                  type: integer
                  description: >-
                    Время жизни инвойса в секундах. После заданного времени,
                    вслучае если оплата не была проведена, инвойс будет отменен.
                    По умолчанию - 1 день
                  nullable: true
                ofd: &ref_0
                  description: Данные для формирования фискального чека
                  $ref: '#/components/schemas/ofdRequest'
              required:
                - store_id
                - amount
                - invoice_id
                - callback_url
                - ofd
              x-apidog-orders:
                - store_id
                - amount
                - invoice_id
                - lang
                - return_url
                - return_error_url
                - callback_url
                - sms
                - ttl
                - ofd
              x-apidog-ignore-properties: []
            example:
              store_id: 6
              amount: 500000
              invoice_id: '123'
              lang: ru
              return_url: https://..
              callback_url: https://..
              ofd:
                - qty: 1
                  price: 2500000
                  mxik: '06401004002000000'
                  total: 25000000
                  package_code: '1506113'
                  name: кроссовки men's low shoes
                - qty: 1
                  price: 2500000
                  mxik: '06401002004000000'
                  total: 2500000
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
                    description: Успешно
                  data:
                    type: object
                    properties:
                      uuid:
                        type: string
                        description: |+
                          Уникальный идентификатор транзакции в Multicard

                        format: uuid
                      store_id:
                        type: integer
                        description: ID кассы, выданный со стороны Multicard
                      amount:
                        type: integer
                        description: Сумма платежа в тийинах
                      invoice_id:
                        type: string
                        description: Идентификатор заказа/пользователя в системе Партнера
                        maxLength: 255
                      return_url:
                        type: string
                        description: >-
                          Ссылка для возврата плательщика на страницу Партнера
                          после неуспешной оплаты
                        nullable: true
                      return_error_url:
                        type: string
                        description: >-
                          Адрес (URL), на который пользователь будет
                          перенаправлен в случае ошибки при оплате
                        nullable: true
                      callback_url:
                        type: string
                        description: URL для отправки callback-запроса
                        nullable: true
                      ofd:
                        description: Данные для формирования фискального чека
                        anyOf:
                          - *ref_0
                          - type: 'null'
                      split:
                        description: >-
                          Информация о разделении платежа между несколькими
                          получателями (split payment). Если null, разделение не
                          используется.
                        anyOf:
                          - &ref_1
                            description: >-
                              Информация о разделении платежа между несколькими
                              получателями (split payment). Если null,
                              разделение не используется.
                            $ref: '#/components/schemas/splitRequest'
                          - type: 'null'
                      sms:
                        type: string
                        description: >-
                          Номер телефона пользователя, куда отправлена ссылка на
                          инвойс
                        nullable: true
                      payment:
                        description: Детали платежа
                        $ref: '#/components/schemas/paymentModel'
                      checkout_url:
                        type: string
                        description: Ссылка на страницу оплаты
                      short_link:
                        type: string
                        description: >-
                          Короткая ссылка на страницу оплаты (полезно в случае
                          формирования QR-кода)
                      deeplink:
                        type: string
                        description: >-
                          Специальная ссылка, которая открывает мобильное
                          приложение или веб-страницу оплаты с заранее
                          подставленными параметрами
                      added_on:
                        type: string
                        description: Дата и время создания инвойса
                      updated_on:
                        type: string
                        description: Дата и время последнего обновления инвойса в системе
                        nullable: true
                    required:
                      - uuid
                      - store_id
                      - amount
                      - invoice_id
                      - return_url
                      - return_error_url
                      - callback_url
                      - ofd
                      - split
                      - sms
                      - payment
                      - checkout_url
                      - short_link
                      - deeplink
                      - added_on
                      - updated_on
                    x-apidog-orders:
                      - uuid
                      - store_id
                      - amount
                      - invoice_id
                      - return_url
                      - return_error_url
                      - callback_url
                      - ofd
                      - split
                      - sms
                      - payment
                      - checkout_url
                      - short_link
                      - deeplink
                      - added_on
                      - updated_on
                    description: Данные инвойса
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
                  summary: Создание инвойса
                  value:
                    success: true
                    data:
                      store_id: 6
                      amount: 100000
                      invoice_id: '123'
                      return_url: https://..
                      callback_url: https://..
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
                      uuid: f6339f31-6a09-11f0-9a1b-00505680eaf6
                      products: null
                      split: null
                      return_error_url: null
                      short_link: https://l.multicard.uz/1m1e12
                      sms: null
                      added_on: '2025-07-26 15:19:06'
                      updated_on: '2025-07-26 15:19:06'
                      payment: '{PaymentModel}'
                      checkout_url: >-
                        https://app.rhmt.uz/invoice/f6339f31-6a09-11f0-9a1b-00505680eaf6
                      deeplink: >-
                        https://multicard.app/payments/checkout/6?params=%7B%22fixed_amount%22%3A100000%2C%22details%22%3A%7B%22invoice_id%22%3A%22123%22%2C%22invoice_uuid%22%3A%22f6339f31-6a09-11f0-9a1b-00505680eaf6%22%7D%7D
                '2':
                  summary: Example
                  value:
                    success: false
                    error:
                      code: ERROR_FIELDS
                      details: Необходимо заполнить «Invoice Id».
          headers: {}
          x-apidog-name: Создание инвойса
        '400':
          description: >-
            Ошибка возникает при отправке запроса с пустым полем, к примеру
            invoice_id
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
                    description: Описание ошибки
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
        x-400:Инвойс просрочен:
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
                  code: ERROR_TRANS_NOT_READY
                  details: Транзакция завершена или не готова
          headers: {}
          x-apidog-name: Инвойс просрочен
      security: []
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729296-run
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
            - *ref_1
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
            - &ref_2
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_2
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
        merchant: &ref_4
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_3
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_3
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
        merchant: *ref_4
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
        merchant_account: *ref_3
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D1%81%D0%BE%D0%B7%D0%B4%D0%B0%D0%BD%D0%BD%D0%BE%D0%BC-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B5-19729297e0.md -->

# Получение информации о созданном инвойсе

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/invoice/{uuid}:
    get:
      summary: Получение информации о созданном инвойсе
      deprecated: false
      description: >-
        Метод позволяет получить детали созданного инвойса. Структура ответа
        аналогична ответу на запрос создания инвойса (выше)
      tags:
        - Оплата на платежной странице Multicard
      parameters:
        - name: uuid
          in: path
          description: uuid инвойса после его создания
          required: true
          example: 527b7ead-b587-11f0-af7c-005056b4367d
          schema:
            type: string
        - name: X-Access-Token
          in: header
          description: ''
          required: false
          example: '{{multicard_token}}'
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
                    description: Успешно
                  data:
                    type: object
                    properties:
                      uuid:
                        type: string
                        description: |+
                          Уникальный идентификатор транзакции в Multicard

                        format: uuid
                      store_id:
                        type: integer
                        description: ID кассы, выданный со стороны Multicard
                      amount:
                        type: integer
                        description: Сумма платежа в тийинах
                      invoice_id:
                        type: string
                        description: Идентификатор заказа/пользователя в системе Партнера
                        maxLength: 255
                      return_url:
                        type: string
                        description: >-
                          Ссылка для возврата плательщика на страницу Партнера
                          после неуспешной оплаты
                        nullable: true
                      return_error_url:
                        type: string
                        description: >-
                          Адрес (URL), на который пользователь будет
                          перенаправлен в случае ошибки при оплате
                        nullable: true
                      callback_url:
                        type: string
                        description: URL для отправки callback-запроса
                        nullable: true
                      ofd:
                        description: Данные для формирования фискального чека
                        anyOf:
                          - &ref_1
                            description: Данные для формирования фискального чека
                            $ref: '#/components/schemas/ofdRequest'
                          - type: 'null'
                      split:
                        description: >-
                          Информация о разделении платежа между несколькими
                          получателями (split payment). Если null, разделение не
                          используется.
                        anyOf:
                          - &ref_0
                            description: >-
                              Информация о разделении платежа между несколькими
                              получателями (split payment). Если null,
                              разделение не используется.
                            $ref: '#/components/schemas/splitRequest'
                          - type: 'null'
                      sms:
                        type: string
                        description: >-
                          Номер телефона пользователя, куда отправлена ссылка на
                          инвойс
                        nullable: true
                      payment:
                        description: Детали платежа
                        $ref: '#/components/schemas/paymentModel'
                      checkout_url:
                        type: string
                        description: Ссылка на страницу оплаты
                      short_link:
                        type: string
                        description: >-
                          Короткая ссылка на страницу оплаты (полезно в случае
                          формирования QR-кода)
                      deeplink:
                        type: string
                        description: >-
                          Специальная ссылка, которая открывает мобильное
                          приложение или веб-страницу оплаты с заранее
                          подставленными параметрами
                      added_on:
                        type: string
                        description: Дата и время создания инвойса
                      updated_on:
                        type: string
                        description: Дата и время последнего обновления инвойса в системе
                        nullable: true
                    required:
                      - uuid
                      - store_id
                      - amount
                      - invoice_id
                      - return_url
                      - return_error_url
                      - callback_url
                      - ofd
                      - split
                      - sms
                      - payment
                      - checkout_url
                      - short_link
                      - deeplink
                      - added_on
                      - updated_on
                    x-apidog-orders:
                      - uuid
                      - store_id
                      - amount
                      - invoice_id
                      - return_url
                      - return_error_url
                      - callback_url
                      - ofd
                      - split
                      - sms
                      - payment
                      - checkout_url
                      - short_link
                      - deeplink
                      - added_on
                      - updated_on
                    description: Данные инвойса
                    x-apidog-ignore-properties: []
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
                x-apidog-ignore-properties: []
              example:
                success: false
                error:
                  code: ERROR_NOT_FOUND
                  details: Объект не найден
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
                  code: ERROR_TRANS_NOT_READY
                  details: Транзакция завершена или не готова
          headers: {}
          x-apidog-name: Инвойс отменен (истек)
        '404':
          description: ''
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
          x-apidog-name: Инвойс не найден
      security: []
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729297-run
components:
  schemas:
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
            - *ref_0
            - type: 'null'
        multicard_user_id:
          type: integer
          description: >-
            ID пользователя в приложении Multicard (если оплата проведена через
            приложение)
          nullable: true
        ofd:
          anyOf:
            - *ref_1
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
            - &ref_2
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_2
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
        merchant: &ref_4
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_3
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_3
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
        merchant: *ref_4
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
        merchant_account: *ref_3
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

<!-- source: https://docs.multicard.uz/%D1%83%D0%B4%D0%B0%D0%BB%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B0%D0%BD%D0%BD%D1%83%D0%BB%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D0%B2%D0%BE%D0%B9%D1%81%D0%B0-19729298e0.md -->

# Удаление (аннулирование) инвойса

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/invoice/{uuid}:
    delete:
      summary: Удаление (аннулирование) инвойса
      deprecated: false
      description: Метод аннулирует созданный инвойс, если он еще не оплачен.
      tags:
        - Оплата на платежной странице Multicard
      parameters:
        - name: uuid
          in: path
          description: 'uuid инвойса для удаления '
          required: true
          example: 1efd8630-b886-11f0-af7c-005056b4367d
          schema:
            type: string
        - name: X-Access-Token
          in: header
          description: ''
          required: false
          example: '{{multicard_token}}'
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
                    type: 'null'
                x-apidog-orders:
                  - success
                  - data
                required:
                  - success
                  - data
              example:
                success: true
                data: []
          headers: {}
          x-apidog-name: Success
        '400':
          description: >-
            При попытке аннулировании уже завершенного платежа, возвращается
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
                  details: Транзакция по инвойсу завершена. Запросите детали платежа
          headers: {}
          x-apidog-name: Аннулирование завершенного инвойса
      security: []
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729298-run
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

<!-- source: https://docs.multicard.uz/%D0%B1%D1%8B%D1%81%D1%82%D1%80%D0%B0%D1%8F-%D0%BE%D0%BF%D0%BB%D0%B0%D1%82%D0%B0-paymego-clickpass-%D0%B8-%D0%B4%D1%80-19729299e0.md -->

# Быстрая оплата (PaymeGo, ClickPass и др.)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/{uuid}/scanpay:
    put:
      summary: Быстрая оплата (PaymeGo, ClickPass и др.)
      deprecated: false
      description: >-
        Списания посредством QR-кода из приложения плательщика. Поддерживаются
        следующие приложения: Payme, Click, Uzum, Anorbank и Xazna.


        В случае успешного выполнения возвращается {PaymentModel}


        При получении таймаута, ошибки с кодом ERROR_DEBIT_UNKNOWN или
        http-статус 500 необходимо проверить статус платежа.
      tags:
        - Оплата на платежной странице Multicard
      parameters:
        - name: uuid
          in: path
          description: uuid инвойса для оплаты через PaymeGo, ClickPass и др.
          required: true
          example: 023874cd-b588-11f0-af7c-005056b4367d
          schema:
            type: string
        - name: X-Access-Token
          in: header
          description: ''
          required: false
          example: '{{multicard_token}}'
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                code:
                  type: string
                  description: Считанный код из приложения
              required:
                - code
              x-apidog-orders:
                - code
              x-apidog-ignore-properties: []
            example:
              code: '50512'
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
          x-apidog-name: Success
        '400':
          description: >-
            В случае показа неправильного QR для быстрого платежа, пользователь
            получает ошибку с кодом 400, и сообщение о неправильном QR 
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
                        description: 'Кодовое сообщение '
                      details:
                        type: string
                        description: Сообщение об ошибке
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
                  details: Неверный код, либо приложение не поддерживается
          headers: {}
          x-apidog-name: 'Показ неправильного QR '
      security: []
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729299-run
components:
  schemas:
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
            - $ref: '#/components/schemas/ofdRequest'
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
            - &ref_0
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_0
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
        merchant: &ref_2
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_1
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_1
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
        merchant: *ref_2
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
        merchant_account: *ref_1
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

<!-- source: https://docs.multicard.uz/callback-success-19729300e0.md -->

# Callback (success)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /:
    post:
      summary: Callback (success)
      deprecated: false
      description: >-
        После успешного списания средств с карты плательщика отправляется
        callback-запрос от платежного шлюза Multicard на URL партнера,
        отправленным в запросе на создание инвойса.


        Запрос отправляется со следующего IP: 195.158.26.90. При получении
        запроса система мерчанта должна проверить поле sign, либо установить
        ограничение на прием запросов только с этого IP-адреса.


        > sign формируется как md5-хеш от строки (без скобок):  

        {store_id}{invoice_id}{amount}{secret} 
          

        Для успешного ответа на указанный запрос необходимо ответить HTTP
        STATUS=200.


        Платеж отменяется (средства будут возвращены плательщику) в случае
        получения HTTP-статуса, отличного от 200 и/или если в теле ответа
        success != true. Значение из поля message будет отображено пользователю
        на странице неуспешной оплаты (чек).


        ``` json

        Пример неуспешного ответа (поле message будет отображено плательщику на
        странице оплаты):

        {

        "success": false,

        "message": "Не найден инвойс"

        }

         ```

        **При таймауте или получении HTTP-статуса 500, система Multicard
        заморозить транзакцию (средства будут заблокированы на карте
        плательщика). В таком случае, коллбэк-запрос может быть отправлен
        повторно. В связи с этим, при получении повторного запроса по инвойсу с
        тем же uuid (уникальный ID транзакции Multicard), если платеж успешный,
        необходимо вернуть успешный статус и обеспечить идемпотентность
        транзакции.**
      tags:
        - Оплата на платежной странице Multicard
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                store_id:
                  type: integer
                  description: |+
                    ID кассы Партнера в системе Multicard

                amount:
                  type: integer
                  description: |+
                    Сумма платежа в тийинах

                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                billing_id:
                  type: string
                  description: Уникальный ID транзакции в системе Партнера
                payment_time:
                  type: string
                  description: |+
                    Время платежа в формате YYYY-mm-dd H:i:s

                phone:
                  type: string
                  description: |+
                    Телефон плательщика в формате 998XXXXXXXXX (при наличии)

                card_pan:
                  type: string
                  description: |-
                    Max length:16
                    Маскированный номер карты
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
                card_token:
                  type: string
                  description: |-
                    Max length:255
                    Токен карты для проведения платежных операций с картой
                uuid:
                  type: string
                  description: Уникальный идентификатор транзакции в Multicard
                receipt_url:
                  type: string
                  description: Ссылка на фискальный чек
                sign:
                  type: string
                  description: |+
                    MD5 хеш от строки: {store_id}{invoice_id}{amount}{secret}

              required:
                - store_id
                - amount
                - invoice_id
                - billing_id
                - payment_time
                - phone
                - card_pan
                - ps
                - card_token
                - uuid
                - receipt_url
                - sign
              x-apidog-orders:
                - store_id
                - amount
                - invoice_id
                - billing_id
                - payment_time
                - phone
                - card_pan
                - ps
                - card_token
                - uuid
                - receipt_url
                - sign
            example:
              store_id: 6
              amount: 20000
              invoice_id: '2024864028760'
              billing_id: '20241214242009869794410864028760'
              payment_time: '2024-12-14 14:36:31'
              phone: '998930601725'
              card_pan: 860030******5959
              ps: uzcard
              card_token: 6225f3c93f7a880142782fa4
              uuid: e60d8ebc-b9fe-11ef-b159-005056b4367d
              receipt_url: >-
                https://dev-checkout.multicard.uz/check/e60d8ebc-b9fe-11ef-b159-005056b4367d
              sign: 553b4292b0f1d8e0e18e6daeb3af3761
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
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729300-run
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

<!-- source: https://docs.multicard.uz/callback-webhooks-19729301e0.md -->

# Callback (webhooks)

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /:
    post:
      summary: Callback (webhooks)
      deprecated: false
      description: >-
        Возможно настройка мерчанта таким образом, что callback-запрос будет
        отправлятся каждый раз при смене статуса платежной транзакции.


        Перечень статусов (отправляемых запросов):


        - draft
            
        - progress
            
        - success
            
        - error
            
        - revert
            

        Запрос отправляется со следующего IP: 195.158.26.90. При получении
        запроса система мерчанта должна проверить поле sign, либо установить
        ограничение на прием запросов только с этого IP-адреса.


        > sign формируется как sha1-хеш от строки (без скобок):  

        {uuid}{invoice_id}{amount}{secret} 
          

        Для успешного ответа на указанный запрос необходимо ответить HTTP
        STATUS=2xx. В противном случае коллбэк-запрос будет повторен 5 раз.
      tags:
        - Оплата на платежной странице Multicard
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                uuid:
                  type: string
                  description: Уникальный идентификатор транзакции в Multicard
                amount:
                  type: integer
                  description: Сумма платежа в тийинах
                invoice_id:
                  type: string
                  description: >-
                    Max length:255

                    Любой идентификатор заказа в системе Партнера. Будет
                    возвращен в callback-запросе. Также по нему можно искать
                    платежи в кабинете мерчанта
                status:
                  type: string
                  description: |-
                    Allowed values (6)
                    draft,
                    progress,
                    success,
                    error,
                    revert,
                    hold
                     hide all
                billing_id:
                  type: string
                  description: Уникальный ID транзакции в системе Партнера
                payment_time:
                  type: string
                  description: Время списания средств с карты клиента
                refund_time:
                  type: 'null'
                  description: Время зачисления средств обратно на карту клиента
                phone:
                  type: string
                  description: >-
                    Max length:12

                    Телефон держателя карты в формате 998XXXXXXXXX
                    (поддерживается только для карт Uzcard и Humo)
                card_pan:
                  type: string
                  description: |-
                    Max length:16
                    Маскированный номер карты
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
                card_token:
                  type: string
                  description: |-
                    Max length:255
                    Токен карты для проведения платежных операций с картой
                receipt_url:
                  type: 'null'
                  description: Ссылка на фискальный чек
                sign:
                  type: string
                  description: |+
                    SHA1 от строки: {uuid}{invoice_id}{amount}{secret}

              required:
                - uuid
                - amount
                - invoice_id
                - status
                - billing_id
                - payment_time
                - refund_time
                - phone
                - card_pan
                - ps
                - card_token
                - receipt_url
                - sign
              x-apidog-orders:
                - uuid
                - amount
                - invoice_id
                - status
                - billing_id
                - payment_time
                - refund_time
                - phone
                - card_pan
                - ps
                - card_token
                - receipt_url
                - sign
            example:
              uuid: e60d8ebc-b9fe-11ef-b159-005056b4367d
              amount: 200000
              invoice_id: test
              status: progress
              billing_id: '20241214242009869794410864028760'
              payment_time: '2024-12-14 14:36:31'
              refund_time: null
              phone: '998930601725'
              card_pan: 860030******5959
              ps: uzcard
              card_token: 6225f3c93f7a880142782fa4
              receipt_url: null
              sign: 5fe1289fbe9cf1834de72789f6629e5f23184974
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
      x-apidog-folder: Оплата на платежной странице Multicard
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729301-run
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

