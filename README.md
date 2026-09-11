# JOY HUB — «Где ключи?»

Production MVP интерактивной задачи Монти Холла для `https://joy-hub.ru`. Пользователь играет без регистрации, сервер не раскрывает расположение ключей до финального решения, а завершённые партии попадают в общую статистику стратегий `SWITCH` и `STAY`.

## Архитектура

```text
Браузер
  │  https://joy-hub.ru, относительные /api/* URL
  ▼
VPS: Nginx :443
  ├── / и /assets/* → /var/www/joy-hub (статический Vite build)
  └── /api/*        → http://10.88.88.2:8080
                           │ WireGuard 10.88.88.0/24
                           ▼
                    Локальный компьютер
                      ├── Spring Boot 4.1.1 / Java 21
                      └── PostgreSQL 16 (только Docker network)
```

Frontend и API имеют один origin, поэтому CORS не нужен. Если локальный компьютер или VPN выключен, Nginx продолжает отдавать статический frontend. Запрос к health завершается за ограниченное время, после чего интерфейс сообщает, что игровой сервер временно недоступен.

Репозиторий разделён по назначению:

```text
backend/src/main/java/ru/joyhub/
  montyhall/domain/       чистые правила игры без Spring
  montyhall/application/  use cases, транзакции, commitment
  montyhall/api/          REST DTO, controllers, ошибки, visitor cookie
  montyhall/persistence/  JPA entity, repository, SQL-агрегация
  config/                 clock и request correlation id

frontend/src/
  app/                    композиция приложения и общие стили
  features/monty-hall/    игровой flow, API, ящики, fairness
  features/stats/         публичная статистика и объяснение

deploy/
  docker/                 production compose и dev overlay
  nginx/                  bootstrap и TLS-конфигурации
  wireguard/              безопасные примеры peer-конфигураций
```

Выбран простой монолит Spring Boot + PostgreSQL. Redis, отдельный сервис статистики и очередь для этой нагрузки не дают практической пользы. Агрегаты статистики вычисляются одним PostgreSQL-запросом только по `COMPLETED` партиям.

## Игровая модель и API

Состояния партии допускают только переходы:

```text
CREATED → CHOICE_MADE → COMPLETED
```

Операции `choice` и `decision` блокируют строку `SELECT ... FOR UPDATE`. Повтор того же `choice` или того же финального `decision` возвращает уже сохранённый ответ. Изменить выбранный ящик, стратегию или завершённую партию нельзя. Поэтому повтор запроса после потери сетевого ответа не увеличивает статистику дважды.

При обычной загрузке frontend делает только `GET /health` и `GET /stats`. Серверная партия создаётся лениво после первого нажатия на ящик: frontend сохраняет выбранный номер, последовательно отправляет `POST /games`, а затем отдельный `POST /games/{id}/choice`. Кнопка «Сыграть ещё раз» только возвращает UI к трём закрытым ящикам. Это не расходует лимит незавершённых партий до следующего реального выбора.

Frontend хранит отправленное, но ещё не подтверждённое действие как `pendingMutation`. После timeout, сетевой ошибки, HTTP 409 или ошибки сервера он запрашивает фактическое состояние через `GET /games/{id}`. Пока исход не установлен, выбрать другой ящик или другую стратегию невозможно: пользователь может повторить проверку либо только то же действие. HTTP 404 предлагает начать новую партию. GET состояния выполняется как read-only операция без pessimistic write lock; mutation endpoints продолжают сериализоваться блокировкой строки.

Все endpoint находятся под `/api/v1`:

| Method | Path | Назначение |
|---|---|---|
| `POST` | `/api/v1/games` | Создать партию; ответ содержит только `gameId` и `commitment` |
| `GET` | `/api/v1/games/{id}` | Восстановить разрешённую для состояния часть партии; чужая партия выглядит как `404` |
| `POST` | `/api/v1/games/{id}/choice` | Передать `{ "box": 1..3 }`, получить открытый и второй закрытый ящики |
| `POST` | `/api/v1/games/{id}/decision` | Передать `{ "strategy": "SWITCH" }` или `STAY`, получить результат и reveal |
| `GET` | `/api/v1/stats` | Агрегаты завершённых партий по каждой стратегии |
| `GET` | `/api/v1/health` | Нечувствительный health backend + database |

Пример с сохранением анонимной cookie:

```bash
curl -sS -c /tmp/joyhub-cookie -X POST http://localhost:8080/api/v1/games
curl -sS -b /tmp/joyhub-cookie \
  -H 'Content-Type: application/json' \
  -d '{"box":3}' \
  http://localhost:8080/api/v1/games/GAME_ID/choice
curl -sS -b /tmp/joyhub-cookie \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"SWITCH"}' \
  http://localhost:8080/api/v1/games/GAME_ID/decision
```

Ошибки возвращаются в формате `application/problem+json` с коротким `code`; stack trace клиенту не отдаётся. UUID visitor хранится год в `HttpOnly; Secure; SameSite=Lax` cookie. Имя, email, телефон и fingerprint не собираются. Партия доступна только visitor, который её создал.

Контракт recovery endpoint зависит от состояния:

```json
{ "gameId": "...", "state": "CREATED", "commitment": "..." }
```

```json
{
  "gameId": "...", "state": "CHOICE_MADE", "commitment": "...",
  "initialChoice": 3, "openedBox": 1, "switchToBox": 2
}
```

Для `COMPLETED` также возвращаются `finalChoice`, `strategy`, `keyBox`, `won` и `nonce`. Поля с секретами отсутствуют в JSON для `CREATED` и `CHOICE_MADE`; JPA entity напрямую в API не сериализуется.

## Проверяемая честность

После первого нажатия, но до отправки выбранного номера, frontend отдельным запросом создаёт игру. В этот момент сервер выбирает `keyBox` криптографическим `SecureRandom`, генерирует 32-байтный случайный `nonce` и считает:

```text
canonical = v1:{gameId}:{keyBox}:{nonce}
commitment = lowercaseHex(SHA-256(UTF-8(canonical)))
```

Запись `CREATED` с ключами, nonce и commitment уже сохранена до `POST /choice`, поэтому первый выбор не может повлиять на расположение ключей. До `decision` наружу выходит только commitment. После завершения API раскрывает `keyBox` и hex-encoded nonce. Frontend заново считает SHA-256 через Web Crypto API и показывает результат проверки. `nonce` и `keyBox` незавершённой игры не попадают в application logs.

## Схема PostgreSQL

Flyway-миграция [V1__create_game_round.sql](backend/src/main/resources/db/migration/V1__create_game_round.sql) создаёт `game_round`:

```text
id UUID PK                 state CREATED | CHOICE_MADE | COMPLETED
key_box INTEGER            nonce VARCHAR(64)        commitment VARCHAR(64) UNIQUE
initial_choice INTEGER     opened_box INTEGER       choice_at TIMESTAMPTZ
strategy SWITCH | STAY     final_choice INTEGER     won BOOLEAN
visitor_id UUID            created_at TIMESTAMPTZ   completed_at TIMESTAMPTZ
version BIGINT
```

Check constraints контролируют диапазоны и полноту каждого состояния. Частичные индексы покрывают завершённые партии `(completed_at)` и `(strategy, won)`, а также незавершённые партии visitor. В production используется `hibernate.ddl-auto=validate`; Hibernate не создаёт и не изменяет схему.

## Локальная разработка

Нужны Java 21, Node.js 22+, Docker с Compose v2 и `curl`. Maven отдельно устанавливать не нужно: `backend/mvnw` загрузит Maven 3.9.9 при первом запуске.

Сначала создайте локальные значения:

```bash
cp .env.example .env
# Замените POSTGRES_PASSWORD в .env; для development допустим отдельный локальный пароль.
```

Запустите только PostgreSQL с loopback-портом из dev overlay:

```bash
docker compose --env-file .env \
  -f deploy/docker/docker-compose.yml \
  -f deploy/docker/docker-compose.dev.yml \
  up -d postgres
```

В первом терминале запустите backend. Значения должны совпадать с `.env`:

```bash
cd backend
export POSTGRES_USER=joyhub
export POSTGRES_PASSWORD='значение-из-.env'
export SPRING_PROFILES_ACTIVE=dev
./mvnw spring-boot:run
```

Во втором терминале:

```bash
cd frontend
npm ci
npm run dev
```

Vite откроется на `http://localhost:5173` и проксирует `/api` на `http://localhost:8080`. Production bundle всегда использует только относительные `/api/...` адреса.

## Тесты и сборка

Backend unit и PostgreSQL integration tests:

```bash
cd backend
JAVA_HOME=/path/to/jdk-21 ./mvnw verify
```

Integration test автоматически запускает `postgres:16-alpine` через Testcontainers. Если Docker недоступен, unit-тесты остаются доступными, а Testcontainers test помечается skipped. Для полной проверки CI и release Docker должен работать.

Frontend component tests и production build:

```bash
cd frontend
npm ci
npm test
npm run build
```

Playwright запускает собранный frontend, перехватывает API детерминированными контрактными fixtures и проверяет switch-flow на `390×844`, stay-flow/keyboard на `1440×900`, видимость ключа после анимации и отсутствие горизонтального scroll на всей матрице `360`, `375`, `390`, `393`, `412`, `430`, `768`, `1024` и `1440` px:

```bash
cd frontend
npx playwright install chromium
npm run test:e2e
```

Отдельный smoke-тест поднимает настоящий PostgreSQL и Spring Boot через Docker Compose, запускает Vite preview и проходит один flow в Chromium без API mocks:

```bash
cd frontend
npm run test:e2e:full
```

Он проверяет отсутствие `POST /games` до первого выбора, порядок `POST /games` → `POST /choice`, сокрытие секрета, восстановимый `CHOICE_MADE`, SWITCH, Web Crypto verification и прирост статистики на одну завершённую партию. Backend integration tests отдельно покрывают REST → transaction → PostgreSQL, ownership, DTO-поля по состояниям и concurrent idempotent decision. CI-конфигурация находится в [.github/workflows/ci.yml](.github/workflows/ci.yml) и запускает backend verify, component tests, build, оба Playwright-набора, Compose validation и Nginx checks.

## Mobile-first интерфейс

Игровая карточка рассчитана сначала на 360–430 px. Ящики всегда образуют три колонки `repeat(3, minmax(0, 1fr))`; у страницы запрещён горизонтальный overflow, кнопки имеют высоту от 50 px, игровой ящик — большую touch-зону. На ширине 640+ кнопки решения становятся двумя колонками, а контент ограничен `max-width: 820px`, поэтому на 1440 px не растягивается.

Отступы страницы включают `env(safe-area-inset-*)`. Все действия выполнены настоящими `<button>`, состояния проговариваются в `aria-label`, ответы объявляются через `aria-live`, focus видим. Игру можно пройти клавиатурой. При `prefers-reduced-motion: reduce` transition и animation сокращаются практически до нуля.

## Production: WireGuard и локальный Docker

На Ubuntu/Debian VPS заранее установите Nginx, WireGuard, Certbot и `rsync`:

```bash
sudo apt update
sudo apt install nginx wireguard certbot rsync
```

Сгенерируйте отдельную пару ключей на каждой машине. Не копируйте private keys в репозиторий:

```bash
umask 077
wg genkey | tee privatekey | wg pubkey > publickey
```

На VPS установите [vps.conf.example](deploy/wireguard/vps.conf.example) как `/etc/wireguard/wg-joyhub.conf`, подставив private key VPS и public key локального компьютера. На локальном компьютере аналогично используйте [local.conf.example](deploy/wireguard/local.conf.example), public IP VPS и public key VPS.

```bash
sudo chmod 600 /etc/wireguard/wg-joyhub.conf
sudo systemctl enable --now wg-quick@wg-joyhub
sudo wg show
```

Проверки:

```bash
# С local computer
ping -c 3 10.88.88.1

# С VPS, после запуска compose
ping -c 3 10.88.88.2
curl --fail http://10.88.88.2:8080/api/v1/health
```

На VPS firewall должен разрешать `80/tcp`, `443/tcp` и `51821/udp`. Порт `8080` в Internet не открывайте. На домашнем роутере не делайте port forwarding к Spring Boot: исходящий WireGuard-туннель с `PersistentKeepalive = 25` работает за NAT.

На локальном компьютере убедитесь, что адрес `10.88.88.2` поднят, затем:

```bash
cp .env.example .env
openssl rand -base64 36   # вставьте результат как POSTGRES_PASSWORD
docker compose --env-file .env -f deploy/docker/docker-compose.yml config
docker compose --env-file .env -f deploy/docker/docker-compose.yml up -d --build
docker compose --env-file .env -f deploy/docker/docker-compose.yml ps
curl --fail http://10.88.88.2:8080/api/v1/health
```

Production compose публикует backend ровно как `10.88.88.2:8080:8080`. У PostgreSQL нет `ports`; он виден только backend-контейнеру. Данные лежат в named volume `joyhub-postgres-data`. Оба контейнера имеют `restart: unless-stopped` и healthcheck.

## Production: frontend, Nginx и TLS на VPS

1. Создайте DNS `A` records для `joy-hub.ru` и `www.joy-hub.ru`, направленные на public IPv4 VPS. Добавляйте `AAAA` только если VPS и firewall корректно обслуживают IPv6.
2. Соберите frontend локально и скопируйте только содержимое `dist`:

```bash
cd frontend
npm ci
npm test
npm run build
rsync -az --delete dist/ VPS_USER@VPS_HOST:/tmp/joy-hub-dist/
rsync -az ../deploy/nginx/ VPS_USER@VPS_HOST:/tmp/joy-hub-nginx/
```

3. На VPS установите файлы атомарно через release directory:

```bash
sudo mkdir -p /var/www/joy-hub
sudo rsync -a --delete /tmp/joy-hub-dist/ /var/www/joy-hub/
sudo chown -R root:root /var/www/joy-hub
sudo find /var/www/joy-hub -type d -exec chmod 755 {} \;
sudo find /var/www/joy-hub -type f -exec chmod 644 {} \;
```

4. До получения сертификата поставьте HTTP bootstrap config:

```bash
sudo cp /tmp/joy-hub-nginx/joy-hub-http-bootstrap.conf /etc/nginx/sites-available/joy-hub.conf
sudo ln -sfn /etc/nginx/sites-available/joy-hub.conf /etc/nginx/sites-enabled/joy-hub.conf
sudo nginx -t
sudo systemctl reload nginx
```

5. Получите один сертификат на оба имени через webroot:

```bash
sudo certbot certonly --webroot -w /var/www/joy-hub \
  -d joy-hub.ru -d www.joy-hub.ru
```

6. Замените bootstrap полным [joy-hub.conf](deploy/nginx/joy-hub.conf), установите общий snippet заголовков, настройте короткую ротацию access logs и перечитайте конфигурацию:

```bash
sudo mkdir -p /etc/nginx/snippets
sudo cp /tmp/joy-hub-nginx/snippets/joy-hub-security-headers.conf /etc/nginx/snippets/
sudo cp /tmp/joy-hub-nginx/joy-hub.conf /etc/nginx/sites-available/joy-hub.conf
sudo cp /tmp/joy-hub-nginx/joy-hub.logrotate /etc/logrotate.d/joy-hub
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

7. Финальные проверки:

```bash
curl -I http://www.joy-hub.ru/                 # 301 на https://joy-hub.ru/
curl -I https://joy-hub.ru/
curl --fail https://joy-hub.ru/api/v1/health
```

Файл [joy-hub-security-headers.conf](deploy/nginx/snippets/joy-hub-security-headers.conf) содержит HSTS, `nosniff`, Referrer-Policy, Permissions-Policy и CSP. Он подключён на HTTPS server-level и повторно только в тех child locations, где собственный `add_header Cache-Control` по правилам Nginx отменяет наследование. CSP разрешает scripts и styles только с текущего origin; `unsafe-inline` не используется. Проверка конфигурации и реальных response headers в disposable Nginx с тестовым сертификатом:

```bash
npm --prefix frontend run build
./scripts/test-nginx.sh

for path in / /index.html /assets/ASSET_NAME.js /api/v1/health; do
  curl -skI "https://joy-hub.ru${path}" | grep -Ei \
    '^(strict-transport-security|x-content-type-options|referrer-policy|permissions-policy|content-security-policy):'
done
```

Nginx ограничивает создание игр до 5 запросов/с на IP с небольшим burst, stats — до 30 запросов/мин, остальные API — до 20 запросов/с. Дополнительно backend разрешает visitor не более 20 незавершённых партий за последние 24 часа. Эти лимиты позволяют быстро нажимать «Сыграть ещё раз», но гасят очевидный спам. IP используется Nginx только для оперативного rate limit и стандартного access log; пример logrotate хранит не больше семи дневных файлов.

Незавершёнными считаются только реально созданные `CREATED` и `CHOICE_MADE` записи за последние 24 часа. Открытие и обновление страницы их больше не создаёт. Старые незавершённые строки пока сохраняются для диагностики; если объём станет значимым, их можно удалять отдельной retention-задачей. Scheduler в приложение в этой итерации не добавлен.

## Эксплуатация

Structured ECS JSON logs доступны через:

```bash
docker compose --env-file .env -f deploy/docker/docker-compose.yml logs -f backend
```

В lifecycle logs присутствует `gameId`, но нет secret/nonce. Каждый HTTP-ответ имеет `X-Request-Id`; безопасный внешний health — `/api/v1/health`, внутренний actuator health не публикуется отдельным host-портом.

Перед обновлением backend сделайте backup базы:

```bash
docker compose --env-file .env -f deploy/docker/docker-compose.yml \
  exec -T postgres pg_dump -U joyhub -d joyhub -Fc > joyhub-$(date +%F).dump
```

Ручными остаются только операции, требующие владения инфраструктурой или секретами: настройка DNS, генерация реальных WireGuard keys, создание `.env`, открытие firewall-портов на VPS, первый запуск Certbot и копирование frontend build на VPS.

Backend использует стабильный Spring Boot 4.1.1 на Java 21. Версии Spring Framework, Jackson, Flyway, PostgreSQL driver, JUnit и Testcontainers управляются Spring Boot BOM; отдельная версия Testcontainers не закреплена.
