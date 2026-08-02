# SNSocial

**Награды за подписку на Telegram-канал и группу ВКонтакте.** Игрок привязывает аккаунт по
короткому коду, плагин проверяет подписку через официальные API, выдаёт награды и откатывает
их, если игрок отписался.

Часть линейки SN (Somikyy Network) — бесплатные плагины с открытым кодом.
Канал автора: [t.me/somikyy](https://t.me/somikyy)

[![build](https://github.com/Somikyy/SNSocial/actions/workflows/build.yml/badge.svg)](https://github.com/Somikyy/SNSocial/actions)

---

## Почему он существует

На RU-рынке (август 2026) награда за подписку — это либо платный hSocialBotsRewards за
**599.99 ₽**, либо fmSocialReward, брошенный автором в день релиза в 2023-м и не проверяющий
подписку вообще. На запрос «как выдать награду за привязку Telegram» комьюнити в июне 2026
отвечало дословно: *«Только изменив код плагина»*. SNSocial закрывает это бесплатно и с
открытым кодом. Подробности и пруфы — в [спеке](docs/SPEC-SNSocial.md).

## Что умеет

- **Telegram**: привязка в один клик по deep-link (`t.me/бот?start=КОД`), проверка подписки
  на канал через `getChatMember`.
- **VK**: привязка кодом в сообщения группы, проверка членства через `groups.isMember`.
- **Награды**: разовые за подписку и периодические (каждые N часов, пока подписан);
  консольные команды выдачи и отката; требование одной сети или обеих сразу; `auto-claim`.
- **Антиабуз**: перепроверка по интервалу и при входе; отписался — награда откатывается и
  без `reclaimable: true` больше не выдаётся; один соцаккаунт = один игрок; ошибка API
  никогда не наказывает игрока.
- **GUI** `/snsocial` со статусом каждой награды.
- **Хранилище** SQLite или MySQL — драйверы уже встроены в Paper, ничего ставить не нужно.
- **Импорт из fmSocialReward** одной командой: кто уже получил награду там, не получит её
  второй раз здесь.
- **PlaceholderAPI**: `%snsocial_telegram_linked%`, `%snsocial_vk_subscribed%`,
  `%snsocial_available%` и другие.
- **Русский из коробки**, английский — `language: en`. Все тексты правятся без пересборки.

## Установка за 15 минут

1. Положи `SNSocial-*.jar` в `plugins/`, запусти сервер — появится `plugins/SNSocial/config.yml`.
2. **Telegram**: создай бота у [@BotFather](https://t.me/BotFather) → токен в
   `telegram.bot-token`; добавь бота **администратором** канала (это требование Telegram —
   без админства подписчиков не видно); канал — в `telegram.channel`.
3. **VK**: в сообществе создай ключ доступа с правами «управление» и «сообщения», включи
   Long Poll API (версия 5.199) и событие «Входящее сообщение», разреши сообщения группы;
   ключ — в `vk.group-token`, числовой ID группы — в `vk.group-id`.
4. `/snsocial reload` — и игроки могут привязываться: `/snsocial link telegram`.

Подробные комментарии — прямо в [config.yml](src/main/resources/config.yml).

## Команды и права

| Команда | Право | Что делает |
|---|---|---|
| `/snsocial` | `snsocial.use` (у всех) | GUI наград |
| `/snsocial link telegram\|vk` | `snsocial.use` | Код привязки |
| `/snsocial unlink telegram\|vk` | `snsocial.use` | Отвязка (с подтверждением) |
| `/snsocial claim <id>` | `snsocial.use` | Забрать награду без GUI |
| `/snsocial status` | `snsocial.use` | Привязки и подписки |
| `/snsocial check <игрок>` | `snsocial.admin` (op) | Форс-перепроверка |
| `/snsocial info <игрок>` | `snsocial.admin` | Привязки и клеймы игрока |
| `/snsocial reload` | `snsocial.admin` | Перезагрузка конфига |
| `/snsocial import fmsocialreward` | `snsocial.admin` | Импорт данных |
| `/snsocial version` | `snsocial.admin` | Версия |

Алиасы: `/social`, `/sns`, `/снс`.

## Совместимость

| Что | Значение |
|---|---|
| Ядра | Paper, Purpur и другие Paper-форки |
| Версии | 1.20.1 — 26.2 (собрано под `api-version: 1.20`, NMS не используется) |
| Java | 17+ |
| Folia | код написан на Folia-планировщиках, но `folia-supported: false` до реального теста |
| Зависимости | нет; PlaceholderAPI — опционально |

## Сборка и самотест

Обычная сборка: `gradle build`. Офлайн, без сети и без сервера:

```
$ bash tools/offline/verify.sh
==> stubs
==> sources
==> resources
    version 26.8.1
==> jar
OK: build/offline/jar/SNSocial-26.8.1.jar
==> compile self-test
==> layering invariant: core/ must not know Bukkit exists
    core is clean
==> run assertions

OK: 81 assertions

SELF-TEST OK
```

81 проверка гоняет всю логику — парсеры ответов Telegram/VK, движок наград, антиабуз, коды
привязки, импортёр — на консервированных ответах, снятых с реальных форматов API. Ядро
(`core/`) не импортирует Bukkit вообще, это проверяется в CI грепом.

## Лицензия

GPL-3.0-or-later. Исходный код открыт полностью — в RU-сегменте после истории с бэкдором
в закрытом jar это не бонус, а пропуск.

---

*Плагин пригодился? Загляни в [t.me/somikyy](https://t.me/somikyy) — там выходят новые
плагины линейки SN, и там же можно заказать плагин или 3D-модель под свой проект.*
