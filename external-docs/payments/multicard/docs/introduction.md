# Multicard — Introduction

_Local copy of Multicard docs. Source URLs noted per section._

---

<!-- source: https://docs.multicard.uz/introduction-4736405f0.md -->

# Introduction

Платежный шлюз Multicard

Документация API для партнеров и мерчантов, подключаемых к платежному шлюзу Multicard.

Стенды:
Sandbox endpoint: https://dev-mesh.multicard.uz/
Production endpoint: https://mesh.multicard.uz/

Тестовая карта:
8600533364098829 2806

Sandbox OTP: 112233

Формат ответов
Все запросы направляются в формате JSON. В каждом ответе присутствует поле success, которое отражает результат выполнения запроса (true / false). В случае, если результат успешен, полезные данные передаются в объекте data. В случае ошибки, возвращается объект error с полями code и details.

Пример ответа с ошибкой:

```json 
{
    "success": false,
    "error": {
        "code": "ERROR_FIELDS",
        "details": "Поле store_id является обязательным"
        }
    }
}
```

