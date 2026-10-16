# Multicard — Авторизация (Auth)

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/%D0%B0%D0%B2%D1%82%D0%BE%D1%80%D0%B8%D0%B7%D0%B0%D1%86%D0%B8%D1%8F-4488543f0.md -->

# Авторизация

> Для отправки авторизированных запросов необходимо получить токен и отправлять его в последующих запросах в заголовке:

`Authorization: Bearer {token}`

В поле expiry указана дата устаревания токена (24 часа с момента выпуска токена). После этого срока запросы к API будут возвращать ошибку (HTTP STATUS) 401 Unauthorized. Не рекомендуется запрашивать токен перед каждым запросом – система поставщика должна быть настроена на кеширование токена на срок его действия.

Для отправки авторизированных запросов необходимо получить токен и отправлять его в последующих запросах в заголовке:

`Authorization: Bearer {token}`

В поле expiry указана дата устаревания токена (24 часа с момента выпуска токена). После этого срока запросы к API будут возвращать ошибку (HTTP STATUS) 401 Unauthorized. Не рекомендуется запрашивать токен перед каждым запросом – система поставщика должна быть настроена на кеширование токена на срок его действия.


---

<!-- source: https://docs.multicard.uz/%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%B8%D0%B5-%D1%82%D0%BE%D0%BA%D0%B5%D0%BD%D0%B0-19729295e0.md -->

# Получение токена

## OpenAPI Specification

```yaml
openapi: 3.0.1
info:
  title: ''
  description: ''
  version: 1.0.0
paths:
  /auth:
    post:
      summary: Получение токена
      deprecated: false
      description: ''
      tags:
        - Авторизация
      parameters: []
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                application_id:
                  type: string
                  description: Идентификатор приложения, выданный со стороны Multicard
                secret:
                  type: string
                  description: Секретный ключ (пароль) приложения
              required:
                - secret
                - application_id
              x-apidog-orders:
                - application_id
                - secret
            example:
              application_id: rhmt_test
              secret: Pw18axeBFo8V7NamKHXX
      responses:
        '200':
          description: ''
          content:
            application/json:
              schema:
                type: object
                properties:
                  token:
                    type: string
                    description: >-
                      JWT-токен. Необходимо передавать в заголовке
                      <Authorization: Bearer {token}>
                  role:
                    type: string
                    description: Роль пользователя или приложения, которому выдан токен
                  expiry:
                    type: string
                    description: >-
                      Время истечения токена, после которого необходимо его
                      запросить заново. Временная зона GMT+5
                required:
                  - token
                  - role
                  - expiry
                x-apidog-orders:
                  - token
                  - role
                  - expiry
              example:
                token: '{{vault:json-web-token}}'
                role: dev
                expiry: '2023-03-18 16:40:31'
          headers: {}
          x-apidog-name: Получение токена
        '400':
          description: >-
            При попытке получения токена без заполнения обязательных полей,
            возвращается ответ с кодом 400, и уведомляет пользователя об
            обязательных полях
          content:
            application/json:
              schema:
                type: object
                properties:
                  errors:
                    type: array
                    items:
                      type: object
                      properties:
                        message:
                          type: array
                          items:
                            type: object
                            properties:
                              message:
                                type: string
                                description: 'Сообщение об обязательном поле для заполнения '
                              field:
                                type: string
                                description: 'Поле для обязательного заполнения '
                            required:
                              - message
                              - field
                            x-apidog-orders:
                              - message
                              - field
                          description: Сообщение об ошибке
                      x-apidog-orders:
                        - message
                    description: 'Ошибка '
                required:
                  - errors
                x-apidog-orders:
                  - errors
              example:
                errors:
                  - message:
                      - message: application_id required
                        field: application_id
                      - message: You must supply a secret
                        field: secret
          headers: {}
          x-apidog-name: Получение токена с пустыми полями
        x-400:Получение токена с неправильными данными:
          description: >-
            При попытке получения токена с неправильными данными, пользователю
            возвращается ошибка с кодом 400, и сообщение о неправильных данных 
          content:
            application/json:
              schema:
                type: object
                properties:
                  errors:
                    type: array
                    items:
                      type: object
                      properties:
                        message:
                          type: array
                          items:
                            type: object
                            properties:
                              message:
                                type: string
                                description: Сообщение о неправильных данных
                            x-apidog-orders:
                              - message
                          description: 'Сообщение об ошибке '
                      x-apidog-orders:
                        - message
                    description: Ошибка
                required:
                  - errors
                x-apidog-orders:
                  - errors
              example:
                errors:
                  - message:
                      - message: Invalid Credentials
          headers: {}
          x-apidog-name: Получение токена с неправильными данными
      security:
        - bearer: []
      x-apidog-folder: Авторизация
      x-apidog-status: released
      x-run-in-apidog: https://app.apidog.com/web/project/1022226/apis/api-19729295-run
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

