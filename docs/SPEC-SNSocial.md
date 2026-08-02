# SPEC — SNSocial

Награды за подписку на Telegram-канал и группу ВКонтакте. Часть линейки SN (Somikyy Network).

Версия спеки: 1.0 · дата: 2 августа 2026 · целевая версия плагина: 26.8.1

---

## 1. Зачем это существует

**Боль.** Русскоязычным серверам нужен инструмент «подпишись на наш ТГК/VK — получи награду»,
а рынок выглядит так (проверено 2 августа 2026):

| Факт | Источник |
|---|---|
| Тема «Награда за привязку Телеграмма» от 18.06.2026: как выдать награду за привязку Telegram. Ответ комьюнити: «Только изменив код плагина» | [spigotmc.ru/threads/29941](https://spigotmc.ru/threads/nagrada-za-privjazku-telegramma.29941/) |
| В той же теме в июле 2026 комьюнити вынуждено советовать fmSocialReward — плагин 2023 года, брошенный автором в день релиза | там же, посты от 20.06 и 04.07.2026 |
| Единственное полноценное решение — hSocialBotsRewards — платное: **599.99 ₽** (в январе 2026 стоило ~250 ₽, цену подняли) | [spigotmc.ru/resources/4495](https://spigotmc.ru/resources/hsocialbotsrewards-plagin-na-nagradu-za-podpisku-na-tgk-dsk-i-vk-gruppy.4495/) |
| fmSocialReward: один день коммитов (21.05.2023), версия так и осталась 1.0, «Разраб пропал без вести» (04.07.2026) | [github.com/FeyMer31/fmSocialReward](https://github.com/FeyMer31/fmSocialReward) |
| Свежий конкурент xGodFreeTg (14.06.2026): 189 ₽, только Telegram, без VK | [spigotmc.ru/threads/29900](https://spigotmc.ru/threads/xgodfreetg-besplatnaja-vydacha-privilegij-za-podpisku-na-telegram-kanal-platno.29900/) |

**Что делает плагин.** Игрок привязывает Telegram и/или VK по короткому коду, плагин проверяет
подписку через официальные API (Telegram `getChatMember`, VK `groups.isMember`), выдаёт награды
консольными командами, периодически перепроверяет подписку и откатывает награду, если игрок
отписался. GUI со списком наград, импорт данных из fmSocialReward.

**Чем бьём.** Бесплатно и с открытым кодом там, где сейчас либо 599.99 ₽, либо мёртвый плагин
без проверки подписки (fmSocialReward проверял только привязку в чужой БД LimboAuth, подписку —
никогда).

**Критерий успеха v1.0.** Админ без опыта поднимает связку «бот + канал + группа» по README за
15 минут; игрок привязывает оба аккаунта, не выходя из игры (клик по ссылке для TG, короткое
сообщение для VK); отписка ловится не позднее интервала перепроверки; ни одного обращения к
диску, сети или БД из главного потока.

---

## 2. Границы задачи

**В v1.0 входит:**
- Telegram: привязка по deep-link коду, проверка подписки на один канал, ответы бота в ЛС.
- VK: привязка по коду в сообщения группы, проверка членства в одной группе, ответы бота.
- Награды: разовые (`subscribe`) и периодические (`periodic`), консольные команды выдачи и
  отката, `reclaimable`, `auto-claim`, требование одной сети или обеих сразу.
- Антиабуз: перепроверка по интервалу и при входе, откат при отписке, память об откате
  (повторно не выдаётся, если не `reclaimable`), один соцаккаунт = один игрок.
- GUI со списком наград и статусами.
- Хранилище SQLite и MySQL (драйверы сервера, без шейдинга).
- Импорт из fmSocialReward.
- PlaceholderAPI-плейсхолдеры (softdepend).
- Локали ru (по умолчанию) и en, все тексты в messages-файлах.

**В v1.0 не входит** (дорожная карта — раздел 10): несколько каналов/групп на сеть, Discord,
вебхуки вместо long polling, мультисерверная синхронизация поверх MySQL, автопостинг в канал.

**Не-цели.** SNSocial не авторизация и не привязка «для входа» (это ниша SNAuthLink); не мост
чата (это SNTelegram); не проверяет лайки/репосты/комментарии — только подписку.

---

## 3. Архитектура

```
core/    — вся логика. Ноль импортов org.bukkit (инвариант, проверяется грепом в CI).
bukkit/  — единственный слой, знающий про сервер: команды, GUI, слушатели, планировщики.
```

Ключевые решения:

1. **Ноль runtime-зависимостей.** HTTP — `java.net.http.HttpClient` (в составе Java SE 17),
   JSON — свой `MiniJson` (~250 строк), JDBC-драйверы SQLite и MySQL поставляет сам Paper
   (подтверждено для 1.20.1: sqlite-jdbc 3.42.0.0 + mysql-connector-j 8.0.33, и для 26.x:
   3.49.1.0 + 9.2.0; официальная дока: «The JDBC Driver is bundled with Paper, so you do not
   need to shade/relocate it» — [docs.papermc.io/paper/dev/using-databases](https://docs.papermc.io/paper/dev/using-databases/)).
   HikariCP Paper НЕ бандлит — поэтому пул не используется: одно соединение на воркер-поток
   с переподключением по ошибке. Для нагрузки «привязки и клеймы» этого достаточно с запасом.

2. **Сетевая работа — на собственных демон-потоках**, не в планировщике сервера:
   `SNSocial-telegram-poller`, `SNSocial-vk-poller`, `SNSocial-worker` (перепроверки, БД).
   Long poll держит поток занятым по 25 секунд — это нормально для выделенного потока и
   неприлично для общего пула. Возврат в игровой поток — только для двух вещей: dispatch
   консольных команд наград и операции над игроком (GUI, сообщения).

3. **Планировщики — Folia-совместимые** (`GlobalRegionScheduler`, `EntityScheduler`):
   доступны в paper-api с 1.20.1 (патч 0417) и на обычном Paper работают прозрачно.
   `folia-supported: false` до реального теста на Folia — флаг не обещание, а факт.

4. **`UNKNOWN` — полноценный статус проверки.** Таймаут API ≠ «не подписан». Ошибка проверки
   блокирует новые выдачи («не смогли проверить») и никогда не триггерит откат («не смогли
   проверить!»). Откат — только по подтверждённому `NOT_SUBSCRIBED`.

5. **Тестируемость через швы.** Единственный сетевой шов — интерфейс `HttpTransport` (один
   метод `postForm`); единственный шов хранилища — интерфейс `Storage`. Самотест подставляет
   консервированные JSON-ответы и `MemoryStorage` и гоняет всю логику без сети и сервера.

6. **GUI без `InventoryView`.** В 1.21 `InventoryView` стал интерфейсом вместо класса;
   байткод, собранный под 1.20 с прямыми вызовами его методов, падает на 1.21+ с
   `IncompatibleClassChangeError` ([spigotmc.org/threads/651754](https://www.spigotmc.org/threads/inventoryview-changed-to-interface-backwards-compatibility.651754/)).
   Поэтому GUI построен на `InventoryHolder`-паттерне: `event.getClickedInventory()`,
   `event.getRawSlot()`, и ни одного вызова методов `InventoryView` в нашем байткоде.

Инварианты:
- `core/` не импортирует `org.bukkit.*` (грепается в CI).
- Главный поток не делает I/O: ни диск, ни сеть, ни JDBC.
- Каждый публичный текст — из messages-файла, UTF-8, ru по умолчанию.
- Telegram/VK user id всегда `long` (Telegram: до 52 бит, [core.telegram.org/bots/api#user](https://core.telegram.org/bots/api#user)).

---

## 4. Механика / правила

Все правила проверены по первоисточникам 2 августа 2026 (исследование в 4 потока, сводка —
в этом разделе и разделе 5).

### 4.1 Telegram

| Правило | Детали | Источник |
|---|---|---|
| Проверка подписки | `getChatMember(chat_id, user_id)`; подписан = status ∈ {`creator`, `administrator`, `member`}; не подписан = {`left`, `kicked`}; `restricted` (только супергруппы) → смотреть поле `is_member` | [api#getchatmember](https://core.telegram.org/bots/api#getchatmember), [api#chatmember](https://core.telegram.org/bots/api#chatmember) |
| Бот обязан быть админом канала | «only guaranteed to work for other users if the bot is an administrator»; в канал бот и добавляется только как админ. Специальные права не нужны | [api#getchatmember](https://core.telegram.org/bots/api#getchatmember) |
| Привязка | Deep link `https://t.me/<bot>?start=<код>` — бот получает `/start <код>`; официально рекомендовано для привязки аккаунтов. Параметр ≤64 символов из A-Z a-z 0-9 _ - | [bots/features#deep-linking](https://core.telegram.org/bots/features#deep-linking) |
| Приём кода | `getUpdates` long polling: `timeout=25`, `allowed_updates=["message"]`, после обработки `offset = max(update_id)+1`. ЛС боту приходят всегда, privacy mode не мешает | [api#getupdates](https://core.telegram.org/bots/api#getupdates), [bots/faq](https://core.telegram.org/bots/faq#what-messages-will-my-bot-get) |
| Формат канала в конфиге | `"@username"` (публичный) или числовой `-100XXXXXXXXXX` (приватный) | [api/bots/ids](https://core.telegram.org/api/bots/ids) |
| Лимиты | Отдельного лимита на getChatMember нет; лимиты отправки ~30 msg/s; при 429 ответ несёт `parameters.retry_after` — уважать | [bots/faq#limits](https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this) |
| Версия Bot API | 10.2 (14 июля 2026) | [api](https://core.telegram.org/bots/api) |

### 4.2 VK

| Правило | Детали | Источник |
|---|---|---|
| Проверка членства | `groups.isMember(group_id, user_id)` → 0/1; допустимые токены: user, **group**, service (официальная JSON-схема VK) | [dev.vk.com/ru/method/groups.isMember](https://dev.vk.com/ru/method/groups.isMember), [VKCOM/vk-api-schema](https://github.com/VKCOM/vk-api-schema) |
| Приём кода | Bots Long Poll: `groups.getLongPollServer(group_id)` → `{server, key, ts}`; цикл `{server}?act=a_check&key&ts&wait=25`; событие `message_new`: `object.message.from_id`, `object.message.text`, `object.message.peer_id` | [dev.vk.com/ru/api/bots-long-poll/getting-started](https://dev.vk.com/ru/api/bots-long-poll/getting-started) |
| Права ключа сообщества | Для `getLongPollServer` ключ обязан иметь scope `manage` (цитата из доки); для ответов `messages.send` — право `messages`. Long Poll и событие «Входящее сообщение» включаются в настройках сообщества | [dev.vk.com/ru/method/groups.getLongPollServer](https://dev.vk.com/ru/method/groups.getLongPollServer) |
| Ошибки long poll | `failed:1` — взять новый ts; `failed:2` — ключ устарел, перезапросить; `failed:3` — перезапросить key и ts | [bots-long-poll/getting-started](https://dev.vk.com/ru/api/bots-long-poll/getting-started) |
| Версия API | `v=5.199` в каждом запросе (последняя документированная на август 2026) | [dev.vk.com/ru/reference/versions](https://dev.vk.com/ru/reference/versions) |
| Лимиты | Ключ сообщества — 20 req/s; при превышении ошибка 6 | [dev.vk.com/ru/api/api-requests](https://dev.vk.com/ru/api/api-requests) |
| Базовый URL | `api.vk.com` и `api.vk.ru` оба живые (проверено запросом 02.08.2026); URL настраивается в конфиге | прямой тест + [api-requests](https://dev.vk.com/ru/api/api-requests) |

### 4.3 Награды

| Правило | Поведение |
|---|---|
| Типы | `subscribe` — разовая за факт подписки; `periodic` — каждые `period-hours`, пока подписан |
| Требования | `requires: [telegram]`, `[vk]` или `[telegram, vk]` (обе сразу — для «подпишись на всё») |
| Выдача | Консольные команды из `commands`, плейсхолдеры `%player%` и `%uuid%`; dispatch строго в игровом потоке |
| Откат | При подтверждённой отписке у активной `subscribe`-награды выполняются `revoke-commands`, факт запоминается в БД |
| Повторная выдача | После отката — только если `reclaimable: true`. Иначе «подписался → забрал → отписался → подписался» фармит награду бесконечно |
| Кулдаун periodic | `lastClaimedAt + period-hours`; остаток показывается в GUI |
| `auto-claim` | Флаг награды: выдавать автоматически при обнаружении доступности (вход, перепроверка), без клика в GUI |
| Порядок вердиктов | LOCKED / ALREADY_CLAIMED → NEED_LINK → NEED_SUBSCRIBE → CHECK_FAILED → COOLDOWN → AVAILABLE — в порядке «что игрок может исправить» |

### 4.4 Антиабуз

| Угроза | Ответ плагина |
|---|---|
| Отписался после награды | Перепроверка по интервалу (`check.interval-minutes`, по умолч. 60) и при входе; откат `revoke-commands` |
| 10 твинков на один ТГ-аккаунт | Уникальность соцаккаунта на сеть: `UNIQUE(telegram_id)`, `UNIQUE(vk_id)`; попытка привязать занятый аккаунт — отказ с именем владельца |
| Отписка → переподписка → повторный клейм | Память об откате в БД (`revoked_at`); без `reclaimable: true` награда заблокирована |
| Unlink → link → повторный клейм | История клеймов при отвязке НЕ удаляется |
| Фарм через смену ника | Ключ данных — UUID, не ник |
| API упало в момент проверки | `UNKNOWN`: выдача на паузу, откат запрещён — игрок не наказывается за наш таймаут |

### 4.5 Привязка

- Код: 6 символов, алфавит без 0/O/1/I/L (руками перепечатывают с телефона), TTL
  `link.code-ttl-minutes` (по умолч. 10), одноразовый, живёт только в памяти.
- Telegram: `/snsocial link telegram` → кликабельная ссылка `t.me/<bot>?start=<код>` (username
  бота плагин узнаёт сам через `getMe`). Принимается и просто код сообщением.
- VK: `/snsocial link vk` → код + кликабельная ссылка на диалог группы; игрок отправляет код
  в сообщения группы.
- Сообщения старше TTL кода при разборе бэклога long poll игнорируются — после рестарта бот
  не отвечает на древние сообщения.
- Отвязка: `/snsocial unlink <сеть>` с подтверждением (повтор команды в течение 30 секунд).

---

## 5. Правила-кандидаты (проверено, но не подтверждено официально)

| Кандидат | Статус | Что делаем |
|---|---|---|
| `getChatMember` по user_id, который боту неизвестен, может вернуть 400 `user not found` вместо `left` | likely (issues tdlib#332, PTB#3896; в доке не оговорено) | В нашем флоу игрок сначала пишет боту, так что кейс маргинален. 400 с `user not found`/`member not found` трактуем как NOT_SUBSCRIBED; прочие ошибки — UNKNOWN |
| `groups.isMember` ключом сообщества для СВОЕЙ группы | likely (схема разрешает group-токен; явного ограничения в доке нет) | Основной путь — токен своей группы; если VK вернёт ошибку прав (15/27), в консоль — подсказка про сервисный ключ (роадмап v1.1: параметр `service-key`) |
| Блокировки VK API с IP зарубежных дата-центров | unverified (жалобы встречаются, официального списка нет) | Базовый URL настраивается (`api.vk.com`/`api.vk.ru`), сетевые ошибки диагностируются понятным текстом |
| `ChatMemberMember.until_date` (платные подписки каналов) влияет на статус | confirmed поле, не подтверждена релевантность | Не используем: платные каналы — не наш кейс. Кандидат на v1.x |

---

## 6. Интерфейсы

### 6.1 Команды

`/snsocial`, алиасы: `/social`, `/sns`, `/снс`.

| Команда | Право | Действие |
|---|---|---|
| `/snsocial` | `snsocial.use` | Открыть GUI наград |
| `/snsocial link telegram\|vk` | `snsocial.use` | Выдать код привязки |
| `/snsocial unlink telegram\|vk` | `snsocial.use` | Отвязать (повтор для подтверждения) |
| `/snsocial claim <id>` | `snsocial.use` | Забрать награду без GUI |
| `/snsocial status` | `snsocial.use` | Привязки и статусы подписок |
| `/snsocial check <игрок>` | `snsocial.admin` | Форс-перепроверка игрока |
| `/snsocial info <игрок>` | `snsocial.admin` | Привязки и клеймы игрока |
| `/snsocial reload` | `snsocial.admin` | Перечитать config.yml и messages |
| `/snsocial import fmsocialreward` | `snsocial.admin` | Импорт из fmSocialReward |
| `/snsocial version` | `snsocial.admin` | Версия и проверка обновлений |

Права: `snsocial.use` — default `true`; `snsocial.admin` — default `op`.

### 6.2 Конфиг (сокращённо; полный — в config.yml с комментариями)

```yaml
config-version: 1
general: { language: ru, update-check: true }
telegram:
  enabled: true
  bot-token: ""            # от @BotFather
  channel: "@my_channel"   # или -1001234567890
vk:
  enabled: true
  group-token: ""          # ключ сообщества со scope manage + messages
  group-id: 0              # числовой ID группы
  api-url: "https://api.vk.com"
check: { interval-minutes: 60, on-join: true, join-delay-seconds: 5 }
link: { code-ttl-minutes: 10 }
storage:
  type: sqlite             # sqlite | mysql
  table-prefix: snsocial_
  mysql: { host, port, database, user, password }
rewards:
  tg_sub:
    requires: [telegram]
    type: subscribe
    display-name: "<green>Награда за подписку на ТГК"
    icon: DIAMOND
    slot: 11
    description: ["<gray>Подпишись на канал", "<gray>и получи 3 алмаза"]
    commands: ["give %player% diamond 3"]
    revoke-commands: []
    reclaimable: false
    auto-claim: false
```

### 6.3 Формат импорта fmSocialReward

Источник: `plugins/fmSocialReward/config.yml`, top-level ключи `player-<Ник>: received`
(код оригинала: `config.set("player-" + name, "received")` —
[FmSocialReward.java](https://github.com/FeyMer31/fmSocialReward/blob/master/src/main/java/ru/feymer/fmsocialreward/FmSocialReward.java)).
UUID не хранятся. Импортёр: срезать префикс → ник → UUID (offline-mode: детерминированный
`OfflinePlayer:`-UUID; online-mode: кэш сервера, некэшированные — в отчёт «пропущено») →
пометить указанную награду выданной (`times_claimed=1`), чтобы не выдать второй раз.

### 6.4 PlaceholderAPI (softdepend)

`%snsocial_telegram_linked%`, `%snsocial_vk_linked%`, `%snsocial_telegram_subscribed%`,
`%snsocial_vk_subscribed%` (да/нет по кэшу статусов), `%snsocial_available%` (число наград,
доступных к получению).

### 6.5 Схема БД

```sql
CREATE TABLE <prefix>players (
  uuid        VARCHAR(36) PRIMARY KEY,
  name        VARCHAR(16),
  telegram_id BIGINT NULL UNIQUE,
  vk_id       BIGINT NULL UNIQUE,
  telegram_linked_at BIGINT NOT NULL DEFAULT 0,
  vk_linked_at       BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE <prefix>claims (
  uuid            VARCHAR(36) NOT NULL,
  reward_id       VARCHAR(64) NOT NULL,
  times_claimed   INT    NOT NULL,
  last_claimed_at BIGINT NOT NULL,
  revoked_at      BIGINT NOT NULL,
  PRIMARY KEY (uuid, reward_id)
);
```

Диалекты: только пересечение SQLite/MySQL; upsert — `REPLACE INTO` (есть в обоих). NULL в
UNIQUE-колонках оба движка допускают многократно — непривязанные игроки не конфликтуют.

---

## 7. Нефункциональные требования

| Требование | Значение | Как проверено |
|---|---|---|
| Runtime-зависимости | 0 (JDBC-драйверы — серверные) | `build.gradle.kts`: единственный compileOnly paper-api (+compileOnly PAPI) |
| Главный поток | ни сети, ни диска, ни JDBC | Ревью: I/O только на своих потоках; в игровой поток — dispatch команд и GUI |
| Работает на | Paper/Purpur 1.20.1 → 26.2 | api-version 1.20 валиден до 26.2 (docs.papermc.io/paper/dev/plugin-yml); байткод Java 17; NMS нет; InventoryView не вызывается |
| Java | 17+ (`--release 17`) | сборка; сервера 26.1+ работают на Java 25 — байткод 17 исполняется |
| Folia | код на Folia-планировщиках, флаг false до теста | plugin.yml + роадмап |
| Локали | ru из коробки, en флагом | messages-ru.txt / messages-en.txt, UTF-8 везде |
| Чистое удаление | в мире не создаётся ничего; после удаления папки плагина следов нет | ручной сценарий |
| Оффлайн-сборка | без Maven Central и papermc.io | `tools/offline/verify.sh` |

---

## 8. Тестирование

Принцип линейки: **правило без фикстуры не считается сделанным.**

Самотест (`tools/offline/verify.sh`) — без сети, без сервера, без JUnit; собирает ядро против
стабов и гоняет `CoreSelfTest` (не попадает в jar):

- **MiniJson**: объекты/массивы/экранирование/`\u`/длинные ID (52 бита)/мусор → исключение.
- **TelegramApi.interpret***: все 6 статусов ChatMember; `restricted+is_member`; ok:false с
  `user not found` → NOT_SUBSCRIBED; 429 → UNKNOWN; разбор getUpdates с offset.
- **VkApi.interpret***: isMember 0/1/extended; error 6 (rate limit) → UNKNOWN; long poll
  `failed:1/2/3`; message_new → from_id/text.
- **RewardEngine**: таблица вердиктов по всем состояниям; UNKNOWN никогда не даёт revoke;
  LOCKED после отката без reclaimable; кулдаун periodic с фейковым временем.
- **LinkCodeService**: TTL, одноразовость, «/start CODE», замена кода, чужая сеть.
- **MemoryStorage + сценарии**: конфликт привязки, unlink не стирает клеймы.
- **Импортёр**: фикстура config.yml формата fmSocialReward → список ников.

SQL-слой: та же логика поверх контракта `Storage`; сам JDBC проверяется ручным сценарием на
реальном сервере (JDK не поставляет драйвер — в офлайн-тесте SQL недостижим честно).

Ручной сценарий перед релизом: привязка TG и VK на живых боте и группе,
выдача, отписка → откат, `reclaimable`, рестарт сервера посреди перепроверки, удаление плагина.

---

## 9. Сборка

Обычная: `gradle build` (нужны Maven Central + repo.papermc.io).
Офлайн: `bash tools/offline/verify.sh` — компиляция против стабов из `tools/offline/bukkit-stubs`
(в jar не попадают), сборка jar, самотест. CI дополнительно сверяет API-поверхность стабов с
реальным paper-api (`api-surface.sh`) — стаб, разошедшийся с реальностью, валит сборку.

---

## 10. Дорожная карта

- **v1.1**: несколько каналов/групп на сеть; сервисный ключ VK как fallback; MySQL-настройки
  пула; `/snsocial stats` (сколько подписок принёс плагин — цифра для поста в канал).
- **v1.2**: Discord (для зарубежных сборок), webhook-режим для TG вместо long polling,
  `folia-supported: true` после реального теста.
- **Осознанно не делаем**: проверку лайков/репостов (API нестабилен, боль не подтверждена);
  свой пул соединений (шейдить HikariCP — конфликт на чужом сервере); платные функции.

---

## 11. Как это работает на канал

Самый прямой крючок линейки: инструмент роста Telegram-каналов от автора Telegram-канала.

1. Пост-боль: «Единственный плагин наград за подписку стоит 600 ₽, бесплатному три года и он
   мёртв» — с пруфами из раздела 1.
2. Пост-релиз: «SNSocial — бесплатно и с открытым кодом» + гайд на 15 минут.
3. Кейсы серверов: «+N подписчиков за неделю» — админам это готовая реклама их ТГК, тебе —
   демонстрация, что инструмент работает. Просить цифры в чате поддержки.
4. Каждый импорт из fmSocialReward — история «переехали с мёртвого плагина за одну команду».
