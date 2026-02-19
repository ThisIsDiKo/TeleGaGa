# Server Setup Guide — TeleGaGa (localModel / Gemma3)

Руководство по подготовке VPS для деплоя бота на ветке `localModel`.
Бот работает **только с Ollama + Gemma3:1b**, без GigaChat, MCP и RAG.

## Требования к серверу

- OS: Ubuntu 22.04 / 24.04 (или Debian 12)
- RAM: минимум **4 GB** (8 GB рекомендуется для Gemma3)
- CPU: 2+ ядра (чем больше, тем быстрее генерация)
- Диск: 10 GB свободных (модель ~700 MB, JDK ~300 MB)
- Доступ по SSH с правами sudo

---

## 1. Первичная настройка сервера

```bash
# Обновить пакеты
sudo apt update && sudo apt upgrade -y

# Установить базовые утилиты
sudo apt install -y curl wget unzip rsync htop
```

---

## 2. Установка JDK 17

```bash
# Ubuntu 22.04/24.04 — JDK 17 есть в стандартных репозиториях
sudo apt install -y openjdk-17-jdk-headless

# Проверить установку
java -version
# Должно показать: openjdk version "17.x.x"
```

Если нужна точная версия или дистрибутив отличается:

```bash
# Альтернатива: через SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 17.0.10-tem
```

После установки убедитесь что путь `/usr/bin/java` рабочий:

```bash
which java          # /usr/bin/java
java -version       # openjdk 17...
```

> В `deploy.properties` укажи `java.path=/usr/bin/java`

---

## 3. Установка Ollama

```bash
# Официальный скрипт установки
curl -fsSL https://ollama.com/install.sh | sh

# Проверить, что Ollama запущен
sudo systemctl status ollama
# Должно быть: Active: active (running)
```

Ollama автоматически создаёт systemd-сервис `ollama.service` и запускает его.

### Настройка автозапуска Ollama

```bash
sudo systemctl enable ollama
sudo systemctl start ollama
```

### Скачать модель Gemma3:1b

```bash
ollama pull gemma3:1b

# Проверить, что модель доступна
ollama list
# Должно показать: gemma3:1b
```

> Скачивание займёт несколько минут (~700 MB). `deploy.sh` делает это автоматически
> во время деплоя, если модель ещё не скачана.

### Проверить API Ollama

```bash
curl http://localhost:11434/api/tags
# Должен вернуть JSON со списком моделей
```

---

## 4. Настройка пользователя и директории деплоя

```bash
# Создать директорию деплоя (если не существует)
sudo mkdir -p /opt/telegaga
sudo chown $USER:$USER /opt/telegaga

# Или, если деплой от другого пользователя:
# sudo useradd -m -s /bin/bash telegaga
# sudo mkdir -p /opt/telegaga
# sudo chown telegaga:telegaga /opt/telegaga
```

---

## 5. Настройка sudo для systemctl (без пароля)

Скрипт `deploy.sh` выполняет `sudo systemctl ...` по SSH.
Чтобы это работало без интерактивного ввода пароля:

```bash
# Открыть sudoers
sudo visudo

# Добавить строку (замените YOUR_USER на имя вашего пользователя):
YOUR_USER ALL=(ALL) NOPASSWD: /bin/systemctl
```

Или более точно — только нужные команды:

```bash
YOUR_USER ALL=(ALL) NOPASSWD: /bin/systemctl start telegaga, /bin/systemctl stop telegaga, /bin/systemctl restart telegaga, /bin/systemctl enable telegaga, /bin/systemctl daemon-reload, /bin/cp /opt/telegaga/telegaga.service /etc/systemd/system/telegaga.service
```

---

## 6. Настройка SSH-ключа (рекомендуется)

На локальной машине:

```bash
# Сгенерировать ключ (если нет)
ssh-keygen -t ed25519 -C "deploy@telegaga"

# Скопировать публичный ключ на сервер
ssh-copy-id -i ~/.ssh/id_ed25519.pub USER@SERVER_HOST
```

В `deploy.properties` указать путь к приватному ключу:

```properties
ssh.key=/Users/dmitriikonovalov/.ssh/id_ed25519
```

---

## 7. Настройка firewall

Бот не принимает входящих соединений — только исходящие (к Telegram API).
Открывать порты не нужно, кроме SSH.

```bash
sudo ufw allow ssh
sudo ufw enable
sudo ufw status
```

> Порт 11434 (Ollama) должен быть закрыт снаружи — бот обращается к нему локально.

---

## 8. Конфигурационный файл

Перед деплоем убедитесь, что `config.properties` в корне проекта содержит:

```properties
# Telegram Bot Token (получить у @BotFather)
telegram.token=YOUR_TELEGRAM_BOT_TOKEN

# Ollama — используется localhost на сервере
ollama.chatModel=gemma3:1b
ollama.embeddingModel=nomic-embed-text

# GigaChat и GitHub можно оставить пустыми — ветка localModel их не использует
```

> `config.properties` **НЕ коммитить** в git (он в `.gitignore`).
> `deploy.sh` автоматически копирует его на сервер при каждом деплое.

---

## 9. Настройка deploy.properties

Скопировать пример и заполнить:

```bash
cp deploy.properties.example deploy.properties
```

```properties
ssh.host=1.2.3.4            # IP или домен сервера
ssh.port=22                  # SSH порт
ssh.user=ubuntu              # Пользователь на сервере
ssh.key=/Users/dmitriikonovalov/.ssh/id_ed25519   # Путь к SSH-ключу

deploy.path=/opt/telegaga    # Директория деплоя на сервере
service.name=telegaga        # Имя systemd-сервиса

java.path=/usr/bin/java      # Путь к Java 17 на сервере
```

---

## 10. Первый деплой

На локальной машине, из корня проекта:

```bash
chmod +x deploy.sh
./deploy.sh
```

Скрипт выполнит:
1. Сборку fat JAR (`./gradlew shadowJar`)
2. Проверку Ollama на сервере
3. Скачивание `gemma3:1b` если нужно
4. Загрузку файлов на сервер
5. Установку и запуск systemd-сервиса

---

## 11. Управление ботом после деплоя

```bash
# Подключиться к серверу
ssh USER@SERVER_HOST

# Статус бота
sudo systemctl status telegaga

# Логи в реальном времени
sudo journalctl -u telegaga -f

# Последние 50 строк логов
sudo journalctl -u telegaga -n 50

# Перезапуск
sudo systemctl restart telegaga

# Статус Ollama
sudo systemctl status ollama

# Список доступных моделей
ollama list

# Проверить, что Ollama отвечает
curl http://localhost:11434/api/tags
```

---

## 12. Обновление бота (без переустановки сервиса)

Используй скрипт быстрого обновления (только JAR):

```bash
./update.sh
```

Этот скрипт пересобирает JAR, делает backup старого, загружает новый и перезапускает сервис.

---

## Чеклист перед первым деплоем

- [ ] Ubuntu 22.04/24.04 установлена
- [ ] `java -version` показывает OpenJDK 17
- [ ] `sudo systemctl status ollama` — active (running)
- [ ] `ollama list` показывает `gemma3:1b` (или deploy.sh скачает сам)
- [ ] `curl http://localhost:11434/api/tags` возвращает JSON
- [ ] SSH-ключ настроен, вход без пароля работает
- [ ] sudo для systemctl настроен без пароля
- [ ] `config.properties` заполнен с рабочим `telegram.token`
- [ ] `deploy.properties` заполнен с правильными SSH и путями
