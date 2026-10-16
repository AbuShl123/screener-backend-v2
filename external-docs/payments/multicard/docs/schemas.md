# Multicard — Schemas (data models)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/applicationmodel-9357257d0.md -->

# applicationModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
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
        - otp_required
        - wallet_contract_num
        - wallet_overdraft
        - wallet_sender_account
        - sms_nickname
        - otp_gateway
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

<!-- source: https://docs.multicard.uz/paymentmodel-9357883d0.md -->

# paymentModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    clearingModel:
      type: object
      properties:
        id:
          type: integer
        merchant: &ref_1
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_0
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_0
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
        - status
        - details
        - amount
        - updated_on
        - added_on
        - payment_time
        - receipt_url
      x-apidog-folder: ''
    taxReceiptModel:
      type: object
      properties:
        receipt:
          type: object
          properties: {}
          x-apidog-orders: []
          description: Объект чека, отправленный в ГНК
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
        - is_refund
        - qr_url
      x-apidog-folder: ''
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
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
        - mfo
        - official_name
        - vat_payer
        - director_pinfl
        - director
        - address
        - account_no
        - active
        - is_commitent
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
          - value
          - name
          - key
          - suggested
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
        - bank_account
        - contract_id
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
        merchant: *ref_1
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
            - service
            - date
            - num
            - fee
            - edm_document_id
            - edm_status
          nullable: true
        merchant_account: *ref_0
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
        - tax_registration
        - view_fields
        - color
        - logo
        - note
        - bg_img
        - qr_url
        - tg_chat_id
        - tax_commission_recipient_tin
        - tax_package_code
        - tax_mxik
        - merchant
        - title
        - merchant_account
        - contract
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
    paymentModel:
      type: object
      properties:
        store_id:
          type: integer
          description: ID кассы, выданный со стороны Multicard
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
          nullable: true
        total_amount:
          type: integer
          description: Сумма платежа в тийинах
        payment_amount:
          type: integer
        commission_amount:
          type: integer
        store_invoice_id:
          type: string
          description: >-
            Любой идентификатор заказа в системе Партнера. Будет возвращен в
            callback-запросе. Также по нему можно искать платежаи в кабинете
            мерчанта
        uuid:
          type: string
          format: uuid
          description: UUID транзакции в Multicard
        status:
          $ref: '#/components/schemas/PaymentStatusEnum'
          description: Статус транзакции
        id:
          type: integer
          description: ID транзакции в Multicard
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
        callback_message:
          type: string
          description: Текст ответа от биллинга мерчанта/поставщика
          nullable: true
        ps_response_msg:
          type: string
          description: Описание ошибки от платежной системы
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
        application:
          $ref: '#/components/schemas/applicationModel'
        store:
          $ref: '#/components/schemas/storeModel'
        tax:
          anyOf:
            - &ref_2
              $ref: '#/components/schemas/taxReceiptModel'
            - type: 'null'
        refund_tax:
          anyOf:
            - *ref_2
            - type: 'null'
        details:
          type: object
          properties: {}
          x-apidog-orders: []
          description: >-
            Поля, необходимые для проведения платежа в биллинге. Используется
            только при оплате за услуги Paynet и МУНИС (мобильная связь,
            гос.платежи и т.п.). Список полей и их название зависит от
            конкретной услуги (store_id)
          nullable: true
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
        - store_id
        - total_amount
        - store_invoice_id
        - callback_url
        - ps
        - payment_amount
        - commission_amount
        - uuid
        - status
        - id
        - card_token
        - card_pan
        - multicard_user_id
        - split
        - details
        - receipt_url
        - kyc_data
        - device_details
        - ofd
        - commission_type
        - ps_uniq_id
        - merchant_id
        - terminal_id
        - ps_response_msg
        - callback_message
        - ps_response_code
        - clearing_id
        - otp_hash
        - payment_time
        - refund_time
        - application
        - push_sent_at
        - tax_receipt_id
        - store
        - tax
        - refund_tax
        - clearing
        - checkout_url
        - added_on
        - billing_id
        - phone
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
        - otp_required
        - wallet_contract_num
        - wallet_overdraft
        - wallet_sender_account
        - sms_nickname
        - otp_gateway
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
          - package_code
          - name
          - total
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

<!-- source: https://docs.multicard.uz/cardmodel-9356533d0.md -->

# cardModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    cardModel:
      type: object
      properties:
        card_token:
          type: string
          description: |-
            Max length:255
            Токен карты для проведения платежных операций с картой
        card_pan:
          type: string
          description: |-
            Max length:16
            Маскированный номер карты
        user_phone:
          type: string
          description: >-
            Max length:12

            Телефон держателя карты в формате 998XXXXXXXXX (поддерживается
            только для карт Uzcard и Humo)
        pinfl:
          type: string
          description: >-
            ПИНФЛ держателя карты (возвращается в случае передачи в запросе на
            создание привязки для карт Uzcard и Humo)

            Max length:14
        expiry:
          type: string
          description: Срок действия карты (YYMM)
        holder_name:
          type: string
          description: Имя и фамилия владельца карты
        redirect_url:
          type: string
          description: >-
            URL для перенаправления после успешного добавления карты
            пользователем
        redirect_decline_url:
          type: string
          description: >-
            URL для перенаправления при неуспешном добавлении карты или отмены
            пользователем
        session_id:
          type: string
          description: >-
            Необходимо сохранить данное значение, оно возвращается в
            callback-запросе в поле payer_id. Также можно проверить состояние
            через метод проверки привязанной карты
        form_url:
          type: string
          description: >-
            URL формы добавления карты (необходимо перенаправить пользователя,
            либо открыть webView)
      x-apidog-orders:
        - card_token
        - card_pan
        - user_phone
        - pinfl
        - expiry
        - holder_name
        - redirect_url
        - redirect_decline_url
        - session_id
        - form_url
      required:
        - card_token
        - card_pan
        - expiry
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

<!-- source: https://docs.multicard.uz/splitrequest-9355022d0.md -->

# splitRequest

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
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

<!-- source: https://docs.multicard.uz/ofdrequest-9355028d0.md -->

# ofdRequest

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
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
          - package_code
          - name
          - total
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

<!-- source: https://docs.multicard.uz/paymentstatusenum-9416791d0.md -->

# PaymentStatusEnum

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
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

<!-- source: https://docs.multicard.uz/paymentserviceenum-9545152d0.md -->

# PaymentServiceEnum

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
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
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```


---

<!-- source: https://docs.multicard.uz/storemodel-9655483d0.md -->

# storeModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
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
        - mfo
        - official_name
        - vat_payer
        - director_pinfl
        - director
        - address
        - account_no
        - active
        - is_commitent
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
          - value
          - name
          - key
          - suggested
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
        - bank_account
        - contract_id
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
        merchant:
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
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
            - service
            - date
            - num
            - fee
            - edm_document_id
            - edm_status
          nullable: true
        merchant_account:
          $ref: '#/components/schemas/merchantAccount'
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
        - tax_registration
        - view_fields
        - color
        - logo
        - note
        - bg_img
        - qr_url
        - tg_chat_id
        - tax_commission_recipient_tin
        - tax_package_code
        - tax_mxik
        - merchant
        - title
        - merchant_account
        - contract
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

<!-- source: https://docs.multicard.uz/merchantmodel-9655500d0.md -->

# merchantModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
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
        - bank_account
        - contract_id
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

<!-- source: https://docs.multicard.uz/billingfieldsmodel-9655508d0.md -->

# billingFieldsModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
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
          - value
          - name
          - key
          - suggested
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

<!-- source: https://docs.multicard.uz/merchantaccount-9655769d0.md -->

# merchantAccount

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
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
        - mfo
        - official_name
        - vat_payer
        - director_pinfl
        - director
        - address
        - account_no
        - active
        - is_commitent
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

<!-- source: https://docs.multicard.uz/taxreceiptmodel-9655813d0.md -->

# taxReceiptModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    taxReceiptModel:
      type: object
      properties:
        receipt:
          type: object
          properties: {}
          x-apidog-orders: []
          description: Объект чека, отправленный в ГНК
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
        - is_refund
        - qr_url
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

<!-- source: https://docs.multicard.uz/clearingmodel-9655929d0.md -->

# clearingModel

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths: {}
components:
  schemas:
    clearingModel:
      type: object
      properties:
        id:
          type: integer
        merchant:
          $ref: '#/components/schemas/merchantModel'
          description: Информация о клиенте (мерчанте)
        recipient_info: &ref_0
          $ref: '#/components/schemas/merchantAccount'
        sender_info: *ref_0
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
        - status
        - details
        - amount
        - updated_on
        - added_on
        - payment_time
        - receipt_url
      x-apidog-folder: ''
    merchantAccount:
      type: object
      properties:
        id:
          type: integer
          description: ID банковских реквизитов
        tin:
          type: string
          description: ИНН или ПИНФЛ
        uuid:
          type: string
          format: uuid
          description: UUID банковских реквизитов
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
        - mfo
        - official_name
        - vat_payer
        - director_pinfl
        - director
        - address
        - account_no
        - active
        - is_commitent
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
        - bank_account
        - contract_id
      x-apidog-folder: ''
  securitySchemes:
    bearer:
      type: http
      scheme: bearer
servers: []
security:
  - bearer: []

```

