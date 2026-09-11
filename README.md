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
  competition/            профиль, попытки, рейтинг и конкурсный API
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

При обычной загрузке frontend делает только `GET /health` и `GET /stats`. Серверная партия создаётся лениво после первого нажатия на ящик: frontend сохраняет выбранный номер и новый UUID в `sessionStorage`, отправляет UUID в обязательном заголовке `Idempotency-Key`, затем последовательно вызывает `POST /games` и отдельный `POST /games/{id}/choice`. Pending creation удаляется из `sessionStorage` только после достоверного подтверждения initial choice: успешного ответа `/choice` либо recovery snapshot в состоянии `CHOICE_MADE` или `COMPLETED`. Кнопка «Сыграть ещё раз» только возвращает UI к трём закрытым ящикам; UUID следующей партии появится после следующего выбора.

Повтор `POST /games` с тем же `Idempotency-Key` возвращает прежние `gameId` и `commitment` при любом состоянии партии. PostgreSQL uniqueness и `INSERT ... ON CONFLICT DO NOTHING` гарантируют одну строку даже для одновременных запросов; response всегда строится из сохранённой строки. Replay выполняется до проверки лимита открытых игр. Если первый response вместе с `Set-Cookie` потерялся, запрос без cookie восстанавливает исходного владельца по idempotency key и сервер повторно выставляет его cookie. Если валидная cookie принадлежит другому visitor, API отвечает `409 IDEMPOTENCY_KEY_CONFLICT` и не меняет владельца.

Frontend хранит отправленное, но ещё не подтверждённое действие как `pendingMutation`. После timeout, сетевой ошибки, HTTP 409 или ошибки сервера он запрашивает фактическое состояние через `GET /games/{id}`. Пока исход не установлен, выбрать другой ящик или другую стратегию невозможно: пользователь может повторить проверку либо только то же действие. HTTP 404 предлагает начать новую партию. GET состояния выполняется как read-only операция без pessimistic write lock; mutation endpoints продолжают сериализоваться блокировкой строки.

Все endpoint находятся под `/api/v1`:

| Method | Path | Назначение |
|---|---|---|
| `POST` | `/api/v1/games` | Создать или повторно получить партию; обязателен `Idempotency-Key: UUID`, ответ содержит только `gameId` и `commitment` |
| `GET` | `/api/v1/games/{id}` | Восстановить разрешённую для состояния часть партии; чужая партия выглядит как `404` |
| `POST` | `/api/v1/games/{id}/choice` | Передать `{ "box": 1..3 }`, получить открытый и второй закрытый ящики |
| `POST` | `/api/v1/games/{id}/decision` | Передать `{ "strategy": "SWITCH" }` или `STAY`, получить результат и reveal |
| `GET` | `/api/v1/stats` | Агрегаты завершённых партий по каждой стратегии |
| `GET` | `/api/v1/health` | Нечувствительный health backend + database |

Конкурсный режим использует отдельные endpoints:

| Method | Path | Назначение |
|---|---|---|
| `GET` | `/api/v1/competition/me` | Профиль, московский день, квота, рекорды и восстанавливаемая попытка/раунд; GET ничего не создаёт |
| `PUT` | `/api/v1/competition/profile` | Создать профиль или изменить публичное имя |
| `POST` | `/api/v1/competition/runs` | Явно начать попытку; обязателен `Idempotency-Key: UUID` |
| `GET` | `/api/v1/competition/runs/{runId}` | Восстановить принадлежащую профилю попытку |
| `POST` | `/api/v1/competition/runs/{runId}/rounds` | Закрепить `expectedRoundNumber`; обязателен `Idempotency-Key`, initial choice не принимается |
| `POST` | `/api/v1/competition/runs/{runId}/abandon` | Добровольно завершить попытку без возврата слота |
| `GET` | `/api/v1/competition/leaderboard?period=TODAY&limit=10` | Публичный рейтинг; поддерживает `ALL_TIME`, максимум 10 строк |

Cookie-based конкурсные mutations и старые `choice`/`decision` требуют `X-JoyHub-CSRF: 1`. Cross-origin JavaScript не может добавить этот заголовок без CORS preflight, а сервер CORS не разрешает; `Sec-Fetch-Site: cross-site` также отклоняется. `SameSite=Lax` остаётся дополнительной защитой.

Пример с сохранением анонимной cookie и безопасным повтором создания:

```bash
CREATION_ID="$(uuidgen)"

curl -sS \
  -H "Idempotency-Key: $CREATION_ID" \
  -c /tmp/joyhub-cookie \
  -X POST \
  http://localhost:8080/api/v1/games

# Повтор с тем же CREATION_ID вернёт ту же партию и повторно выставит cookie,
# даже если cookie первого ответа не дошла до браузера.
curl -sS \
  -H "Idempotency-Key: $CREATION_ID" \
  -c /tmp/joyhub-cookie \
  -X POST \
  http://localhost:8080/api/v1/games

curl -sS -b /tmp/joyhub-cookie \
  -H 'Content-Type: application/json' \
  -H 'X-JoyHub-CSRF: 1' \
  -d '{"box":3}' \
  http://localhost:8080/api/v1/games/GAME_ID/choice
curl -sS -b /tmp/joyhub-cookie \
  -H 'Content-Type: application/json' \
  -H 'X-JoyHub-CSRF: 1' \
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

## Соревнование по серии побед

Режим добровольный: обычная игра остаётся первым экраном, не требует имени и не имеет конкурсной квоты. Сохранение имени, просмотр рейтинга, reload и открытие формы не расходуют попытку. Слот резервирует только «Начать попытку». Лимит задаёт `COMPETITION_DAILY_ATTEMPT_LIMIT` (по умолчанию 5).

Одна `competition_run` содержит последовательность обычных раундов до первого поражения. Победа в одной транзакции завершает `game_round` и увеличивает `score` на один; поражение переводит попытку в `LOST`, не обнуляя score. `ABANDONED` означает добровольное завершение, `EXPIRED` — дневную границу. Следующая попытка начинается с нуля, личный рекорд равен `MAX(score)` и не уменьшается.

Конкурсный день задан как `Europe/Moscow`: `[00:00, следующие 00:00)`. Backend использует инжектируемый `Clock`, сохраняет `competition_date`, `started_at`, `expires_at` и `rules_version=1`. Время `acceptedAt` берётся после блокировок; при `acceptedAt == expiresAt` попытка истекла. Cron не нужен: чтение показывает эффективное истечение, следующая команда/старт закрывает просроченную ACTIVE-попытку. Replay уже завершённого decision после полуночи возвращает прежний результат без второго очка.

Порядок блокировок для конкурсных mutations — `competition_player → competition_run → game_round`. PostgreSQL uniqueness защищает один ACTIVE run, один номер раунда и один незавершённый раунд. Start и create-round получают клиентские UUID до первого запроса. Один `expectedRoundNumber` возвращает уже закреплённые gameId/commitment даже при разных ключах из двух вкладок. Раунд создаётся только после выбора ящика в UI, а choice передаётся отдельным запросом: commitment сформирован раньше initial choice.

После reload frontend читает `/competition/me` и восстанавливает ACTIVE/LOST run и `CREATED`, `CHOICE_MADE` либо `COMPLETED` round. Versioned журнал в `sessionStorage` хранит только run/round, idempotency UUID, gameId, исходный commitment, box и pending strategy. Credential, visitorId, keyBox и nonce туда не попадают. При неоднозначной ошибке альтернативный box/strategy заблокирован до server recovery или безопасного повтора. Если исходный commitment потерян вместе с журналом, результат сохраняется, но UI не заявляет независимую проверку.

Владение подтверждает отдельная 32-байтная CSPRNG credential cookie. Production имя — `__Host-joyhub_competition`, атрибуты `HttpOnly; Secure; SameSite=Lax; Path=/`; dev/smoke используют отдельное имя без `__Host-` и допускают HTTP. В БД хранится SHA-256 hash и expiry, TTL 365 дней проверяется сервером. Имя, public ID/tag, visitor cookie, gameId и idempotency key не дают доступ к профилю. Конкурсные строки на старых `/games/{id}` endpoints дополнительно требуют credential владельца. Очистка cookies/другой браузер создают новый профиль; восстановление по имени не обещается.

Имя нормализуется NFC, обрезается, повторные пробелы схлопываются; разрешены 2–20 Unicode code points из букв, цифр, пробелов, дефиса и подчёркивания. Одинаковые имена допустимы и различаются stable public tag. Имя выводится только как текст. Для локального административного исключения после проверки используйте `UPDATE competition_player SET excluded_from_leaderboard=TRUE WHERE public_id='...';` через закрытый доступ к БД; публичного admin endpoint нет.

«Сегодня» берёт лучший score каждого профиля за текущую дату, `ALL_TIME` — лучший score этой версии правил. Нулевые результаты не публикуются. Место равно `1 + число профилей с большим score`, поэтому возможны `1, 2, 2, 4`; время достижения служит только стабильным порядком. Публичный response кешируется на 15 секунд и не содержит персональных данных `me`; личный результат приходит из private `no-store` endpoint. All-time — доска рекордов, а не рейтинг мастерства: большая история участия даёт больше возможностей установить серию.

## Схема PostgreSQL

Flyway-миграция [V1__create_game_round.sql](backend/src/main/resources/db/migration/V1__create_game_round.sql) создаёт `game_round`, [V2__add_creation_request_id.sql](backend/src/main/resources/db/migration/V2__add_creation_request_id.sql) добавляет idempotency token, а [V3__add_competition.sql](backend/src/main/resources/db/migration/V3__add_competition.sql) добавляет конкурсные таблицы и nullable связь старых раундов. V1/V2 не изменялись; накопленные партии остаются обычными и не входят в рейтинг задним числом.

```text
id UUID PK                 creation_request_id UUID NOT NULL UNIQUE
state CREATED | CHOICE_MADE | COMPLETED
key_box INTEGER            nonce VARCHAR(64)        commitment VARCHAR(64) UNIQUE
initial_choice INTEGER     opened_box INTEGER       choice_at TIMESTAMPTZ
strategy SWITCH | STAY     final_choice INTEGER     won BOOLEAN
visitor_id UUID            created_at TIMESTAMPTZ   completed_at TIMESTAMPTZ
version BIGINT
```

`competition_player` хранит внутренний/public UUID, public tag, display name, hash/expiry credential, timestamps и флаг исключения. `competition_run` хранит owner, start request UUID, московскую дату/номер попытки, статус, монотонный score, время достижения, границы, rules version и optimistic version. В `game_round` добавлены nullable `competition_run_id` и `competition_round_number`; `NULL` означает обычный режим. Общая статистика продолжает считать все завершённые раунды обоих режимов по прежним формулам.

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
npx playwright install chromium webkit
npm run test:e2e
npm run test:e2e:webkit
```

Основной локальный прогон использует Chromium. Отдельная команда WebKit запускает короткий smoke; в CI `PLAYWRIGHT_WEBKIT=1` включает оба движка после установки их системных зависимостей.

Отдельный smoke-тест поднимает настоящий PostgreSQL и Spring Boot через Docker Compose, запускает Vite preview и проходит один flow в Chromium без API mocks:

```bash
cd frontend
npm run test:e2e:full
```

Он проверяет отсутствие `POST /games` до первого выбора, UUID в `Idempotency-Key`, повтор create с тем же ответом, порядок `POST /games` → `POST /choice`, сокрытие секрета, восстановимый `CHOICE_MADE`, SWITCH, Web Crypto verification и прирост статистики. Competition smoke проходит настоящий профиль → start → W,W,L, теряет реальные ответы победного и проигрышного decision после commit, восстанавливает score без повторного начисления и проверяет leaderboard. Детерминированный random включается только disposable профилем `full-stack-test`; production использует `SecureGameRandomSource`.

Backend integration tests покрывают V3, W,W,L, первое поражение, новый run с нуля, replay/quota, конкурентные start/round, credential/CSRF/ownership, московскую границу, переименование и места `1,2,2,4`. Frontend tests закрепляют opt-in, явный start, reload между create-round и choice, lost-decision recovery, pause, periods и обычные регрессии. CI-конфигурация находится в [.github/workflows/ci.yml](.github/workflows/ci.yml) и запускает backend verify, component tests, build, оба Playwright-набора, Compose validation и Nginx checks.

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

Реальный VPS уже использует отдельную certificate-конфигурацию в `/etc/nginx/ssl/joy-hub.ru/`. Не заменяйте её путями Certbot из репозиторного шаблона: сначала перенесите только новые application/location settings в действующий конфиг, сохраните его копию и выполните `nginx -t`. Шаги Certbot ниже относятся только к чистой установке без существующего TLS.

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

Nginx ограничивает создание игр до 5 запросов/с на IP с небольшим burst, конкурсные записи профиля/start/round/abandon — до 5 запросов/с с burst 10, stats — до 30 запросов/мин, остальные API — до 20 запросов/с. Дополнительно backend разрешает visitor не более 20 незавершённых партий за последние 24 часа, а конкурсный профиль — не более 5 новых попыток за московский день. IP-лимит только сглаживает всплески и не заменяет профильную квоту, поэтому несколько игроков за общим NAT не делят пять попыток. IP используется Nginx только для оперативного rate limit и стандартного access log; пример logrotate хранит не больше семи дневных файлов.

Незавершёнными считаются только реально созданные `CREATED` и `CHOICE_MADE` записи за последние 24 часа. Открытие и обновление страницы их больше не создаёт. Старые незавершённые строки пока сохраняются для диагностики; если объём станет значимым, их можно удалять отдельной retention-задачей. Scheduler в приложение в этой итерации не добавлен.

## Эксплуатация

Structured ECS JSON logs доступны через:

```bash
docker compose --env-file .env -f deploy/docker/docker-compose.yml logs -f backend
```

Read-only integrity/product report находится в [competition-audit.sql](deploy/sql/competition-audit.sql). Первые запросы должны вернуть ноль расхождений score и нумерации; остальные считают стартовавшие браузерные профили, повторные попытки и возврат в другой день. Частоту recovery оценивайте по structured событиям `*_replayed`; это сигналы повторов, а не число людей и не доказательство сетевого сбоя:

```bash
docker compose --env-file .env -f deploy/docker/docker-compose.yml exec -T postgres \
  psql -U joyhub -d joyhub -v ON_ERROR_STOP=1 < deploy/sql/competition-audit.sql
docker compose --env-file .env -f deploy/docker/docker-compose.yml logs backend | \
  grep -E 'competition_.*_replayed'
```

В lifecycle logs присутствуют `game_created`, `game_create_replayed`, start/end/expiry попытки, подтверждённый score и competition replay с безопасными runId/gameId. В них нет credential, `Idempotency-Key`, secret или nonce. Каждый HTTP-ответ имеет `X-Request-Id`; безопасный внешний health — `/api/v1/health`, внутренний actuator health не публикуется отдельным host-портом.

Перед обновлением backend сделайте backup базы:

```bash
docker compose --env-file .env -f deploy/docker/docker-compose.yml \
  exec -T postgres pg_dump -U joyhub -d joyhub -Fc > joyhub-$(date +%F).dump
```

Ручными остаются только операции, требующие владения инфраструктурой или секретами: настройка DNS, генерация реальных WireGuard keys, создание `.env`, открытие firewall-портов на VPS, первый запуск Certbot и копирование frontend build на VPS.

Backend использует стабильный Spring Boot 4.1.1 на Java 21. Версии Spring Framework, Jackson, Flyway, PostgreSQL driver, JUnit и Testcontainers управляются Spring Boot BOM; отдельная версия Testcontainers не закреплена.
