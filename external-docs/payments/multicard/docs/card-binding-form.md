# Multicard — Привязка карт (форма) / Card binding via form

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B0-%D0%BA%D0%B0%D1%80%D1%82-%D1%84%D0%BE%D1%80%D0%BC%D0%B0-4488545f0.md -->

# Привязка карт (форма)

> Методы для привязки карты клиента в системе Партнера.

Процедура привязки карты:

- Партнер отправляет [запрос на получение ссылки](#cc37a427-5aae-481c-9f2e-0ee6dbdf6bc0) на страницу привязки карты.
    
- Если в переданном запросе отправлен callback_url, то Партнер ожидает [получение вебхука (коллбэка)](#72642c0c-44eb-4e6f-8f48-7c4c8c7a819f) после успешного добавления карты пользователем.
    
- Если callback_url не передан, то Партнер имеет возможность [запросить состояние привязки карты](#2bc94106-f7d2-442b-83d4-83678be5049d).

#### Методы для привязки карты клиента в системе Партнера.

Процедура привязки карты:

- Партнер отправляет [запрос на получение ссылки](#cc37a427-5aae-481c-9f2e-0ee6dbdf6bc0) на страницу привязки карты.
    
- Если в переданном запросе отправлен callback_url, то Партнер ожидает [получение вебхука (коллбэка)](#72642c0c-44eb-4e6f-8f48-7c4c8c7a819f) после успешного добавления карты пользователем.
    
- Если callback_url не передан, то Партнер имеет возможность [запросить состояние привязки карты](#2bc94106-f7d2-442b-83d4-83678be5049d).


---

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8-%D0%BD%D0%B0-%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D1%83-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729302e0.md -->

# Получение ссылки на страницу привязки карты

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/bind:
    post:
      summary: Получение ссылки на страницу привязки карты
      deprecated: false
      description: >-
        Метод для создания сессии по привязке карты. Срок активности сессии – 15
        минут.


        Необходимо перенаправить пользователя по URL (открыть WebView),
        указанному в поле data.form_url.


        Для получения результата можно передать callback_url, тогда будет
        отправлен callback-запрос, либо проверять состояние с помощью метода
        проверки привязанной карты.
      tags:
        - Привязка карт (форма)
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                redirect_url:
                  type: string
                  description: >
                    URL для перенаправления после успешного добавления карты
                    пользователем
                redirect_decline_url:
                  type: string
                  description: >+
                    URL для перенаправления при неуспешном добавлении карты или
                    отмены пользователем

                store_id:
                  type: integer
                  description: |+
                    ID кассы, выданный со стороны Multicard

                callback_url:
                  type: string
                  description: >+
                    Если передан, то по заданному URL будет отправлен
                    callback-запрос (webhook) при успешном добавлении карты.
                    Формат запроса приведен ниже

                pinfl:
                  type: string
                  description: >-
                    ПИНФЛ клиента. Если передан, то будет осуществлена проверка
                    на принадлежность карты к данному лицу (только для
                    Uzcard/Humo)
                  minLength: 14
                  maxLength: 14
                phone:
                  type: string
                  description: >-
                    Номер телефона пользователя в формате 998XXXXXXXXX.
                    Обязателен для карт Humo. Если передан, то будет проверено
                    соответствие с номер смс-информаирования на карте (для
                    Uzcard/Humo)
              required:
                - redirect_url
                - redirect_decline_url
                - store_id
                - callback_url
                - phone
              x-apidog-orders:
                - redirect_url
                - redirect_decline_url
                - store_id
                - callback_url
                - phone
                - pinfl
              description: |+

            example:
              redirect_url: https://site.uz/success.html
              redirect_decline_url: https://site.uz/decline.html
              store_id: 6
              callback_url: https://site.uz/card-callback
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
                      session_id:
                        type: string
                        description: >+
                          Необходимо сохранить данное значение, оно возвращается
                          в callback-запросе в поле payer_id. Также можно
                          проверить состояние через метод проверки привязанной
                          карты

                      form_url:
                        type: string
                        description: >+
                          URL формы добавления карты (необходимо перенаправить
                          пользователя, либо открыть webView)

                    required:
                      - session_id
                      - form_url
                    x-apidog-orders:
                      - session_id
                      - form_url
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data:
                  session_id: 67f8dd24e9800
                  form_url: https://dev-checkout.multicard.uz/card/67f8dd24e9800
          headers: {}
          x-apidog-name: Привязка карты
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
                  details: 'Не переданы обязательные поля: store_id'
          headers: {}
          x-apidog-name: Ошибочная валидация
      security: []
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729302-run
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

<!-- source: https://docs.multicard.uz/callback-%D0%B7%D0%B0%D0%BF%D1%80%D0%BE%D1%81-19729303e0.md -->

# Callback-запрос

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
      summary: Callback-запрос
      deprecated: false
      description: >-
        Данный запрос отправляется со стороны системы Multicard в систему
        Партнера.


        При успешном добавлении карты пользователем, на переданный в поле
        callback_url адрес, приходит следующий запрос.


        В поле card_token указан токен карты для проведения платежных
        транзакций.


        В случае, если на callback-запрос система Партнера ответила
        http-статусом, отличным от 200, то запрос отправляется через 60 секунд
        (всего 5 попыток).
      tags:
        - Привязка карт (форма)
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                id:
                  type: integer
                application_id:
                  type: integer
                  description: Идентификатор приложения, выданный со стороны Multicard
                payer_id:
                  type: string
                  description: |+
                    Значение поля session_id из ответа на запрос привязки карты

                card_pan:
                  type: string
                  description: |-
                    Max length:16
                    Маскированный номер карты
                card_token:
                  type: string
                  description: |-
                    Max length:255
                    Токен карты для проведения платежных операций с карто
                phone:
                  type: string
                  description: >-
                    Max length:12

                    Телефон держателя карты в формате 998XXXXXXXXX
                    (поддерживается только для карт Uzcard и Humo)
                holder_name:
                  type: string
                  description: |-
                    Max length:100
                    Имя держателя карт
                pinfl:
                  type: 'null'
                  description: >-
                    Default:

                    ПИНФЛ держателя карты (возвращается в случае передачи в
                    запросе на создание привязки для карт Uzcard и Humo)

                    Max length:14
                ps:
                  type: string
                  description: |-
                    Allowed values (5)
                    uzcard,humo,visa,mastercard,unionpay hide all
                    Платежная система
                status:
                  type: string
                  description: >-
                    Allowed values (3)

                    active,

                    draft,

                    deleted
                     
                    hide all

                    Статус привязки. active - карта успешно привязана, можно
                    проводить платежные операции по токену карты
                added_on:
                  type: string
                  description: |+
                    Время добавления карты в формате YYYY-mm-dd H:i:s

                card_status:
                  type: 'null'
                sms_inform:
                  type: 'null'
                is_multicard:
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
                - is_multicard
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
                - is_multicard
            example:
              id: 3554547
              application_id: 9
              payer_id: 688470c8060af
              card_pan: 986027******1519
              card_token: 6225f3c93f7a880142782fa4
              phone: '99891234567'
              holder_name: AAAAA BBBBB
              pinfl: null
              ps: humo
              status: active
              added_on: '2025-03-17 03:19:12'
              card_status: null
              sms_inform: null
              is_multicard: false
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
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729303-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D1%81%D0%BE%D1%81%D1%82%D0%BE%D1%8F%D0%BD%D0%B8%D1%8F-%D0%BF%D1%80%D0%B8%D0%B2%D1%8F%D0%B7%D0%BA%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729304e0.md -->

# Проверка состояния привязки карты

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/bind/{session_id}:
    get:
      summary: Проверка состояния привязки карты
      deprecated: false
      description: >-
        С помощью данного метода можно проверить состояние привязки карты
        (вместо ожидания callback-запроса)
      tags:
        - Привязка карт (форма)
      parameters:
        - name: session_id
          in: path
          description: Идентификатор сессии окна привязки карты, время действия 15 минут
          required: true
          example: 69089f9d03f26
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
                      application_id:
                        type: integer
                        description: >-
                          Идентификатор приложения, выданный со стороны
                          Multicard
                      payer_id:
                        type: string
                        description: >+
                          Значение поля session_id из ответа на запрос привязки
                          карты

                      card_pan:
                        type: string
                        description: |-
                          Max length:16
                          Маскированный номер карты
                      card_token:
                        type: string
                        description: |+
                          Max length:255
                          Токен карты для проведения платежных операций с картой

                      phone:
                        type: string
                        description: >-
                          Max length:12

                          Телефон держателя карты в формате 998XXXXXXXXX
                          (поддерживается только для карт Uzcard и Humo)
                      holder_name:
                        type: string
                        description: |+
                          Max length:100
                          Имя держателя карты

                      pinfl:
                        type: 'null'
                        description: >-
                          Default:

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
                        type: string
                        description: >+
                          Allowed values (3)

                          active,

                          draft,

                          deleted

                          hide all

                          Статус привязки. active - карта успешно привязана,
                          можно проводить платежные операции по токену карты

                      added_on:
                        type: string
                        description: |+
                          Время добавления карты в формате YYYY-mm-dd H:i:s

                      card_status:
                        type: object
                        properties:
                          code:
                            type: integer
                            description: |+
                              Состояние карты. 0 - карта активна

                          description:
                            type: string
                            description: |+
                              Описание блокировки карты

                        required:
                          - code
                          - description
                        x-apidog-orders:
                          - code
                          - description
                        description: >-
                          Текущий статус карты (например, активна или
                          заблокирована). Содержит код и текстовое описание.
                      sms_inform:
                        type: boolean
                        description: >-
                          Признак подключения SMS-уведомлений (true = включены,
                          false = отключены).
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
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data:
                  id: 55
                  application_id: 1
                  payer_id: '1233321'
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
          headers: {}
          x-apidog-name: Проверка привязанной карты
      security: []
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729304-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D0%B8%D0%BD%D1%84%D0%BE%D1%80%D0%BC%D0%B0%D1%86%D0%B8%D0%B8-%D0%BE-%D0%BA%D0%B0%D1%80%D1%82%D0%B5-%D0%BF%D0%BE-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D1%83-19729305e0.md -->

# Получение информации о карте по токену

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/{card_token}:
    get:
      summary: Получение информации о карте по токену
      deprecated: false
      description: ''
      tags:
        - Привязка карт (форма)
      parameters:
        - name: card_token
          in: path
          description: Идентификатор добавленной карты в систему
          required: true
          example: 6225f3c93f7a880142782fa4
          schema:
            type: string
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties: {}
            examples: {}
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
                        description: >+
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
                          Max length:12

                          Телефон держателя карты в формате 998XXXXXXXXX
                          (поддерживается только для карт Uzcard и Humo)
                      holder_name:
                        type: string
                        description: |-
                          Max length:100
                          Имя держателя карты
                      pinfl:
                        type: 'null'
                        description: >-
                          Default:ПИНФЛ держателя карты (возвращается в случае
                          передачи в запросе на создание привязки для карт
                          Uzcard и Humo)

                          Max length:14
                      ps:
                        type: string
                        description: |+
                          Allowed values (5)
                          uzcard,
                          humo,
                          visa,
                          mastercard,
                          unionpay
                          hide all
                          Платежная система

                      status:
                        type: string
                        description: >-
                          Allowed values (3)

                          active,

                          draft,

                          deleted

                          hide all

                          Статус привязки. active - карта успешно привязана,
                          можно проводить платежные операции по токену карты
                      added_on:
                        type: string
                        description: |+
                          Время добавления карты в формате YYYY-mm-dd H:i:s

                      card_status:
                        type: object
                        properties:
                          code:
                            type: integer
                            description: |+
                              Состояние карты. 0 - карта активна

                          description:
                            type: string
                            description: |+
                              Описание блокировки карты

                        required:
                          - code
                          - description
                        x-apidog-orders:
                          - code
                          - description
                        description: >-
                          Текущий статус карты (например, активна или
                          заблокирована). Содержит код и текстовое описание.
                      sms_inform:
                        type: boolean
                        description: >-
                          Признак подключения SMS-уведомлений (true = включены,
                          false = отключены).
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
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
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
          headers: {}
          x-apidog-name: Проверка привязанной карты
        '400':
          description: >-
            При поиске несуществуюещей карты, возвращается ошибка с кодом 400,
            также с текстом
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
                  code: ERROR_CARD_NOT_FOUND
                  details: Неверный номер карты или срок действия
          headers: {}
          x-apidog-name: 'Поиск несуществующей карты '
      security: []
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729305-run
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

<!-- source: https://docs.multicard.uz/%D0%BF%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B0-%D0%BF%D1%80%D0%B8%D0%BD%D0%B0%D0%B4%D0%BB%D0%B5%D0%B6%D0%BD%D0%BE%D1%81%D1%82%D0%B8-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-%D0%BA-%D0%BF%D0%B8%D0%BD%D1%84%D0%BB-19729306e0.md -->

# Проверка принадлежности карты к ПИНФЛ

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/check-pinfl:
    post:
      summary: Проверка принадлежности карты к ПИНФЛ
      deprecated: false
      description: >-
        Метод осуществляет проверку соответствия владельца карты. Поддерживается
        только для карт Uzcard и Humo.


        При успешном выполнении в ответе в поле data возвращается true (ПИНФЛ
        соответствует) или false (не соответствует); в случае, если результат
        неизвестен - null
      tags:
        - Привязка карт (форма)
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                pan:
                  type: string
                  description: |+
                    Номер карты (Uzcard или Humo)

                pinfl:
                  type: string
                  description: |-
                    Min length:14
                    Max length:14
                    ПИНФЛ держателя карты для проверки
              required:
                - pan
                - pinfl
              x-apidog-orders:
                - pan
                - pinfl
            example:
              pan: '8600303655375959'
              pinfl: '12345678901234'
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
                    type: boolean
                    description: >+
                      true - соответствует; false - не соответствует; null -
                      результат неизвестен

                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data: false
          headers: {}
          x-apidog-name: Проверка принадлежности карты к ПИНФЛ
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
                  details: Необходимо заполнить «Pinfl».
          headers: {}
          x-apidog-name: Ошибочная валидация
        x-400:Запрос с менее 14 символами в поле ПИНФЛ:
          description: >-
            При запросе с заполнением менее 14 символов в поле ПИНФЛ,
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
                  details: pinfl должен состоять из 14 цифр.
          headers: {}
          x-apidog-name: Запрос с менее 14 символами в поле ПИНФЛ
      security: []
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729306-run
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

<!-- source: https://docs.multicard.uz/%D0%B0%D0%BD%D0%BD%D1%83%D0%BB%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D0%B0-%D0%BA%D0%B0%D1%80%D1%82%D1%8B-19729307e0.md -->

# Аннулирование токена карты

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /payment/card/{card_token}:
    delete:
      summary: Аннулирование токена карты
      deprecated: false
      description: Запрос на удаление карты (аннулирование токена карты)
      tags:
        - Привязка карт (форма)
      parameters:
        - name: card_token
          in: path
          description: ''
          required: true
          example: 6225f3c93f7a880142782fa4
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
                    type: array
                    items:
                      type: string
                required:
                  - success
                  - data
                x-apidog-orders:
                  - success
                  - data
              example:
                success: true
                data: []
          headers: {}
          x-apidog-name: Отвязка карты
        '400':
          description: >-
            При попытке аннулировании с недействительным номером карты,
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
                  code: ERROR_CARD_NOT_FOUND
                  details: Неверный номер карты или срок действия
          headers: {}
          x-apidog-name: Аннулирование карты по неверному токену (card_token)
      security: []
      x-apidog-folder: Привязка карт (форма)
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729307-run
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

