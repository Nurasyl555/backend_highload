# SIS #1 — System Design: T3. To-Do / Task Tracker API

**Автор:** Калмаханбет Нұрасыл Дінмұхаммедұлы (ID: 23B030297, Группа П2)  
**Дисциплина:** Backend for Highloaded Environment (Fall 2026)  
**Тема:** T3. To-Do / Task Tracker API  

---

## 1. Требования к системе (Requirements)

### 1.1. Функциональные требования (FR)
1. **CRUD операции над задачами:** создание задачи (`title`, `description`, `priority`, `deadline`), чтение по ID, полное и частичное редактирование, удаление (перевод в статус `ARCHIVED`).
2. **Управление жизненным циклом и статусами:** переходы между состояниями `TODO` → `IN_PROGRESS` → `DONE` / `ARCHIVED`.
3. **Фильтрация и выборка списков:** получение списка задач пользователя с фильтрацией по `status`, `priority`, диапазону `deadline` и курсорной пагинацией.

### 1.2. Нефункциональные требования (NFR & SLO)
* **Масштаб базы пользователей:** `1 000 000` зарегистрированных пользователей (~`200 000` DAU).
* **Профиль нагрузки:** `2 000 RPS` в среднем (`80%` чтение / `20%` запись, соотношение Read:Write = `4:1`).
* **SLO по задержке (Latency):** `p50 < 20 мс`, `p99 < 100 мс`.
* **SLO по доступности (Availability):** `99.9%` (бюджет ошибок: не более `43.8 минут` простоя в месяц / `8.8 часов` в год).
* **Консистентность:** строгая консистентность для владельца задачи (*read-your-own-writes*) за счет инвалидации кэша при записи (`delete on write`).

---

## 2. Оценка нагрузки и ресурсов (Back-of-the-Envelope Estimation)

Расчет выполнен по 5-шаговой методике курса:

### Шаг 1. Трафик (Reads & Writes)
* **Средний RPS:** `2 000 RPS`
  * **Reads (80%):** $2\,000 \times 0.8 = 1\,600\text{ RPS}$ (просмотр списка задач, фильтрация по статусам/дедлайнам).
  * **Writes (20%):** $2\,000 \times 0.2 = 400\text{ RPS}$ (создание задач, смена статуса, редактирование).

### Шаг 2. Пиковая нагрузка (Peak) и пропускная способность сети
Принимаем дневной **Peak Factor = 3x** (утренние часы планирования рабочего дня):
* **Peak Total RPS:** $2\,000 \times 3 = 6\,000\text{ RPS}$
* **Peak Read RPS:** $1\,600 \times 3 = 4\,800\text{ RPS}$
* **Peak Write RPS:** $400 \times 3 = 1\,200\text{ RPS}$
* **Сетевой трафик (Bandwidth):**
  * Чтение (ответ со списком из 10–15 задач $\approx 4\text{ КБ}$):
    * Средний: $1\,600 \times 4\text{ КБ} \approx 6.4\text{ МБ/с}$
    * Пиковый: $4\,800 \times 4\text{ КБ} \approx 19.2\text{ МБ/с}$
  * Запись (тело запроса $\approx 500\text{ Б}$):
    * Средний: $400 \times 500\text{ Б} \approx 0.2\text{ МБ/с}$ (в пике $0.6\text{ МБ/с}$).

### Шаг 3. Объем хранения данных (Data Volume)
* **Размер одной записи (`task`) с учетом индексов и MVCC-заголовков PostgreSQL:**
  * `id` (8B) + `user_id` (8B) + `title` (~100B) + `description` (~250B) + `status` (2B) + `priority` (2B) + `deadline`, `created_at`, `updated_at` (24B) + `version` (4B) + заголовок строки кучи (`xmin`, `xmax`, `ctid` ~24B) + B-Tree индексы (~80B) $\approx \mathbf{500\text{ Bytes}}$.
* **Прирост данных:**
  * При `200 000` активных пользователей в сутки (DAU), создающих в среднем по `5` новых задач в день:
    $$\text{Новых задач в сутки} = 200\,000 \times 5 = 1\,000\,000\text{ задач/день}$$
  * Суточный объем: $1\,000\,000 \times 500\text{ Б} = \mathbf{0.5\text{ ГБ/день}}$.
  * Годовой объем: $0.5\text{ ГБ} \times 365 = \mathbf{182.5\text{ ГБ/год}}$.
  * За 5 лет хранения (Retention): $182.5\text{ ГБ} \times 5 \approx \mathbf{0.91\text{ ТБ}}$.
* **Вывод по хранилищу:** $< 1\text{ ТБ}$ за 5 лет полностью помещается на **одном узле PostgreSQL** (Primary + Read Replica для отказоустойчивости). Горизонтальное шардирование БД не требуется и создало бы лишнюю сложность.

### Шаг 4. Рабочий набор для кэша (Working Set)
* Большинство запросов обращаются к текущим активным задачам (`TODO`, `IN_PROGRESS`).
* Полагая в среднем `20` активных задач на каждого из `1 000 000` пользователей:
  $$\text{Working Set} = 1\,000\,000 \times 20 \times 500\text{ Б} = \mathbf{10\text{ ГБ}}$$
* Весь рабочий набор полностью помещается в память одного инстанса **Redis (16 GB RAM)**.
* При целевом **Cache Hit Ratio = 95%**:
  * Эффективная задержка чтения ($t_{cache} = 0.5\text{ мс}$, $t_{db} = 5\text{ мс}$):
    $$T_{eff} = 0.95 \times 0.5 + 0.05 \times 5.0 = \mathbf{0.725\text{ мс}}$$
  * Нагрузка на чтение в БД падает в 20 раз:
    * Средняя: $1\,600 \times 0.05 = \mathbf{80\text{ RPS}}$
    * Пиковая: $4\,800 \times 0.05 = \mathbf{240\text{ RPS}}$

### Шаг 5. Расчет емкости и пулов по закону Литтла ($L = \lambda \times W$)
1. **Уровень приложения (Stateless Task API):**
   * В пике $\lambda = 6\,000\text{ RPS}$, среднее время ответа $W = 20\text{ мс} = 0.02\text{ с}$:
     $$L_{api} = 6\,000 \times 0.02 = \mathbf{120\text{ одновременных запросов (in-flight)}}$$
   * Используем **Java 21 с Virtual Threads** (легковесные потоки не блокируют потоки ОС на сетевом вводе-выводе).
   * Для обеспечения доступности `99.9%` и выдерживания пика разворачиваем **3 рабочих инстанса + 1 резервный (итого 4 инстанса по 4 vCPU, 8 GB RAM)** за балансировщиком нагрузки.
2. **Пул соединений к PostgreSQL (HikariCP):**
   * В пике до БД доходит $\lambda_{db} = 240\text{ (reads)} + 1\,200\text{ (writes)} = 1\,440\text{ RPS}$.
   * Среднее время выполнения индексированного запроса в БД $W_{db} = 5\text{ мс} = 0.005\text{ с}$:
     $$L_{db} = 1\,440 \times 0.005 = \mathbf{7.2\text{ активных соединений}}$$
   * С учетом пиков и возможных ожиданий блокировок задаем размер пула **20 соединений на инстанс** (суммарно 80 открытых коннектов со всех 4 инстансов) и ограничиваем конкурентные запросы к БД семафором (`Semaphore(20)`).

---

## 3. Архитектура системы (High-Level Design)

![Architecture Diagram](./architecture.png)

*(Исходный файл схемы находится в репозитории: `architecture.excalidraw`)*

### Ключевые архитектурные решения:
1. **Транспорт и Безопасность:**
   * На внешнем периметре (API Gateway / Nginx) включены **HTTP/2 и HTTP/3 (QUIC)** поверх **TLS 1.3** (1 RTT на хэндшейк, поддержка Session Tickets с общим ключом на балансировщиках и ALPN `h2`).
   * Между API Gateway и бэкенд-сервисами используются постоянные пулы соединений (`Keep-Alive`, `TCP_NODELAY`), исключающие накладные расходы на установку TCP-соединений при каждом запросе.
2. **Разделение Stateless и Stateful:**
   * Инстансы `Task Service` полностью **Stateless** — не хранят сессий или локальных файлов, могут перезапускаться и масштабироваться горизонтально за секунды.
   * Состояние вынесено в **Redis** (кэш) и **PostgreSQL** (Primary для записи и строгого чтения + синхронная/полусинхронная Read Replica для аналитики и фоллбэка).

---

## 4. Контракт API (API Contract)

Выбран стиль **REST over HTTP/2 (JSON)** для внешнего API, так как сервис обслуживает веб- и мобильные клиенты (открытый стандарт, нативная поддержка браузерами, прозрачность при отладке). Для крупных списков задач включено сжатие `zstd` / `gzip`.

### Таблица эндпоинтов (Версионирование в URL path: `/api/v1`)

| Метод | Путь | Назначение | Идемпотентность | Коды ответа |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/tasks` | Создание задачи | Требует заголовок `Idempotency-Key: <UUID>` | `201 Created`, `400`, `429`, `503` |
| `GET` | `/api/v1/tasks` | Список задач с фильтрацией и курсором | Да (`SAFE`, `IDEMPOTENT`) | `200 OK` |
| `GET` | `/api/v1/tasks/{id}` | Получение задачи по ID | Да (`SAFE`, `IDEMPOTENT`) | `200 OK`, `404 Not Found` |
| `PUT` | `/api/v1/tasks/{id}` | Полная замена задачи (с проверкой `version`) | Да (`IDEMPOTENT`) | `200 OK`, `409 Conflict` |
| `PATCH` | `/api/v1/tasks/{id}/status` | Изменение статуса (`{"status": "DONE", "version": 2}`) | Да (установка конкретного состояния) | `200 OK`, `409 Conflict` |
| `DELETE` | `/api/v1/tasks/{id}` | Архивация / удаление задачи | Да (`IDEMPOTENT`) | `204 No Content`, `404` |

### Пример идемпотентного создания задачи (`POST /api/v1/tasks`)

**Запрос:**
~~~http
POST /api/v1/tasks HTTP/2
Host: api.tasktracker.kz
Authorization: Bearer <JWT>
Idempotency-Key: 8f14e45f-ceea-467a-a8f8-159b32c5f7b1
X-Request-Deadline-Ms: 90
Content-Type: application/json

{
  "title": "Сдать SIS #1 по Highload",
  "description": "Подготовить расчеты, схему БД и отчет",
  "priority": 1,
  "deadline": "2026-10-20T18:00:00Z"
}
~~~

**Ответ (`201 Created`):**
~~~json
{
  "id": 904218,
  "user_id": 4217,
  "title": "Сдать SIS #1 по Highload",
  "status": "TODO",
  "priority": 1,
  "deadline": "2026-10-20T18:00:00Z",
  "version": 1,
  "created_at": "2026-10-14T09:30:00Z"
}
~~~

---

## 5. Модель данных, Индексы и Транзакции (Data Model & PostgreSQL)

### 5.1. DDL Схема таблиц
~~~sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status SMALLINT NOT NULL DEFAULT 1,   -- 1: TODO, 2: IN_PROGRESS, 3: DONE, 4: ARCHIVED
    priority SMALLINT NOT NULL DEFAULT 2, -- 1: HIGH, 2: MEDIUM, 3: LOW
    deadline TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 1,       -- Оптимистическая блокировка
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    key UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    response_code INT NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
~~~

### 5.2. Индексы и обоснование для планировщика (Query Planner)
Основной горячий запрос системы — получение списка задач пользователя по статусу, отсортированных по дедлайну (с курсорной пагинацией вместо медленного `OFFSET`):

~~~sql
SELECT id, title, status, priority, deadline, version
FROM tasks
WHERE user_id = $1
  AND status = $2
  AND (deadline, id) > ($3, $4)
ORDER BY deadline ASC, id ASC
LIMIT 20;
~~~

**Проектирование индексов (Leftmost Prefix Rule: равенства первыми, затем сортировка):**
~~~sql
-- 1. Составной покрывающий индекс (Covering Index) для мгновенного Index Only Scan
CREATE INDEX idx_tasks_user_status_deadline
ON tasks (user_id, status, deadline ASC, id ASC)
INCLUDE (title, priority, version);

-- 2. Частичный индекс (Partial Index) для дашборда активных задач с горящими дедлайнами
CREATE INDEX idx_tasks_active_deadline
ON tasks (user_id, deadline ASC)
WHERE status IN (1, 2); -- Только TODO и IN_PROGRESS
~~~

* **Почему это дает `< 1 мс`:** B-Tree индекс глубиной 3–4 уровня сразу отфильтровывает `user_id` и `status`, читает 20 уже отсортированных по `deadline` записей из листьев и благодаря `INCLUDE` возвращает результат через **Index Only Scan** без обращений к страницам кучи (Heap).

### 5.3. Защита от аномалий (Lost Update) и настройка MVCC
* **Проблема Lost Update:** при уровне изоляции `Read Committed` параллельное редактирование задачи с двух устройств пользователя перезапишет одно из изменений.
* **Решение (Optimistic Locking):** каждое обновление проверяет номер версии:
  ~~~sql
  UPDATE tasks
  SET status = $1, version = version + 1, updated_at = now()
  WHERE id = $2 AND user_id = $3 AND version = $4;
  ~~~
  Если обновлено `0` строк — транзакция возвращает `409 Conflict`.
* **Идемпотентность `POST`:** сохранение новой задачи и запись `idempotency_keys` выполняются в одной `@Transactional` транзакции. При гонке двух ретраев уникальный индекс `PRIMARY KEY (key)` отсекает дубликат. Старые ключи очищаются фоновым воркером через 24 часа.
* **Настройки против MVCC Bloat и зависших блокировок:**
  * `statement_timeout = '2s'`
  * `lock_timeout = '500ms'`
  * `idle_in_transaction_session_timeout = '10s'` (чтобы забытые транзакции не мешали `autovacuum` очищать мертвые версии строк).

---

## 6. Стратегия кэширования (Caching Strategy)

Используется двухуровневая схема кэширования с основным паттерном **Cache-Aside + Delete on Write**:

1. **L1 Cache (In-process Caffeine):**
   * Хранит до `10 000` самых горячих карточек задач с коротким `TTL = 3 с`.
   * Защищает Redis от всплесков чтения одного и того же ключа и объединяет одновременные промахи в один запрос (`LoadingCache` coalescing).
2. **L2 Cache (Shared Redis):**
   * **Ключи:**
     * `task:{id}` — детали задачи (`TTL = 10 мин`).
     * `user:{user_id}:tasks:status:{status}:page1` — первая страница списка задач пользователя (`TTL = 5 мин`).
   * **Инвалидация при записи:** при любом `POST / PUT / PATCH / DELETE` приложение фиксирует транзакцию в БД и выполняет `DEL` соответствующих ключей в Redis (и рассылает событие в Pub/Sub для сброса L1). Мы используем `DEL`, а не `SET`, чтобы избежать гонки *stale-write race* при параллельных обновлениях.
   * **Настройки Redis:** `maxmemory 16gb`, политика вытеснения `allkeys-lfu`, клиентский таймаут `50 мс`.

### Защита от классических сбоев кэша:
* **Cache Stampede (Dogpiling):** при промахе в L2 используется распределенный лок (`SET lock:tasks:user:{id} NX PX 1000`) — только один поток идет в PostgreSQL и прогревает кэш, остальные ждут 20 мс.
* **Cache Avalanche (Лавинообразное истечение):** ко всем TTL добавляется случайный джиттер `ttl = base_ttl * (0.9 + 0.2 * random())`, чтобы ключи не истекали одновременно.
* **Cache Penetration (Запрос несуществующих ID):** если задача не найдена в БД, в Redis записывается маркер `"NULL"` с коротким `TTL = 45 с`.

---

## 7. Сценарии отказов и защита системы (Failure Modes & Resilience)

| Сценарий сбоя | Что происходит без защиты | Механизм защиты в нашей архитектуре |
| :--- | :--- | :--- |
| **1. Обрыв сети при создании задачи (Unknown Outcome)** | Клиент делает retry и создает 2–3 одинаковые задачи. | Клиент генерирует UUID в заголовке `Idempotency-Key` и повторяет запрос с **Exponential Backoff + Full Jitter** (до 3 попыток). Сервер атомарно проверяет ключ в БД и возвращает сохраненный `201 Created`. |
| **2. Падение или зависание Redis (Cache Outage)** | Потоки приложения висят на сокетах Redis, пул исчерпывается, сервис падает. | Жесткий таймаут клиента Redis `50 мс` + **Circuit Breaker**. При сбое Redis чтение идет напрямую в PostgreSQL (Primary + Read Replica). Индексы B-Tree позволяют БД выдержать все `1 600 RPS` чтения (~35% емкости одного узла PG). |
| **3. Всплеск нагрузки / замедление БД (Overload)** | Бесконечная очередь запросов копится в памяти до OOM, клиенты массово ретраят (Retry Storm). | Ограниченные очереди (**Bounded Queues**), `Semaphore(20)` перед пулом БД с таймаутом ожидания `30 мс`. При перегрузке — мгновенный **Load Shedding** (`503 Service Unavailable` + `Retry-After`). |
| **4. Просроченный запрос (Expired Deadline Budget)** | Сервер продолжает выполнять тяжелый SQL-запрос, когда клиент уже закрыл соединение по таймауту. | Сквозной **Deadline Budget (`100 мс`)**: перед походом в кэш или БД проверяется оставшееся время. Если бюджет исчерпан, работа отменяется до обращения к базе. |
| **5. Падение одного инстанса Task API** | Потеря пользовательских сессий и активных запросов. | Инстансы полностью **Stateless**. Балансировщик нагрузки исключает узел по health-check за 2 секунды, остальные 3 инстанса забирают трафик без потери данных. |

---

## 8. AI Disclosure

В соответствии с академической политикой курса (Level D): генеративный ИИ (Gemini) использовался для проверки арифметических расчетов емкости по закону Литтла, валидации структуры составных покрывающих индексов PostgreSQL и форматирования таблиц в Markdown-отчете. Все архитектурные решения, выбор компромиссов (trade-offs) и параметры отказоустойчивости проверены и изучены автором для устной защиты.