# Настройка конфигурации TeleGaGa

## Быстрый старт

После клонирования репозитория выполните следующие шаги:

### 1. Создайте файл конфигурации

Скопируйте шаблон конфигурации:

```bash
cp config.properties.template config.properties
```

### 2. Заполните параметры

Откройте `config.properties` и заполните все обязательные параметры:

```properties
# Токен Telegram бота (получите у @BotFather)
telegram.token=YOUR_TELEGRAM_BOT_TOKEN

# Ключ авторизации GigaChat в формате Base64
gigachat.authKey=YOUR_GIGACHAT_AUTH_KEY

# Базовый URL GigaChat API (обычно не требует изменений)
gigachat.baseUrl=https://gigachat.devices.sberbank.ru

# Модель GigaChat (обычно не требует изменений)
gigachat.model=GigaChat
```

### 3. Где получить токены

#### Telegram Bot Token
1. Откройте Telegram и найдите @BotFather
2. Отправьте команду `/newbot`
3. Следуйте инструкциям для создания нового бота
4. Скопируйте полученный токен в параметр `telegram.token`

#### GigaChat Auth Key
1. Зарегистрируйтесь на платформе GigaChat (https://developers.sber.ru/)
2. Создайте новый проект
3. Получите ключ авторизации (Authorization Key) в формате Base64
4. Скопируйте ключ в параметр `gigachat.authKey`

### 4. Запустите приложение

```bash
# Сборка проекта
JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home ./gradlew build

# Запуск
JAVA_HOME=/Users/dmitriikonovalov/Library/Java/JavaVirtualMachines/openjdk-17.0.1/Contents/Home ./gradlew run
```

## Безопасность

**ВАЖНО:** Файл `config.properties` содержит конфиденциальные данные и добавлен в `.gitignore`.

- **НИКОГДА** не коммитьте `config.properties` в git
- Храните токены в безопасном месте
- Не делитесь токенами с третьими лицами
- При компрометации токенов немедленно их обновите

## Проверка конфигурации

При запуске приложение проверит наличие и корректность конфигурации:

- Если файл `config.properties` отсутствует, будет создан шаблон и выведена инструкция
- Если какие-то параметры не заполнены, приложение покажет список отсутствующих значений
- При успешной загрузке конфигурации в консоли появится сообщение с замаскированными токенами

## Структура конфигурации

```
TeleGaGa/
├── config.properties          # Ваш рабочий файл конфигурации (НЕ в git)
├── config.properties.template # Шаблон конфигурации (в git)
└── CONFIG_SETUP.md           # Эта инструкция
```

## Устранение неполадок

### Ошибка: "Конфигурационный файл не найден"
Создайте `config.properties` из шаблона (см. шаг 1)

### Ошибка: "Не заполнены обязательные параметры"
Проверьте, что все 4 параметра в `config.properties` имеют значения

### Ошибка при запуске бота
Проверьте корректность токена Telegram бота

### Ошибка при подключении к GigaChat
Проверьте корректность ключа авторизации GigaChat
