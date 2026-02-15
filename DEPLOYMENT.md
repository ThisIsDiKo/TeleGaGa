# Инструкция по развертыванию TeleGaGa на VPS

Эта инструкция описывает процесс развертывания бота TeleGaGa на удаленном VPS сервере.

## Созданные файлы

### 1. deploy.properties.example
Шаблон конфигурации для деплоя. Содержит параметры:
- **SSH подключение**: хост, порт, пользователь, опционально путь к SSH ключу
- **Путь развертывания** на сервере
- **Название systemd сервиса**
- **Путь к Java** на сервере

### 2. deploy.sh
Скрипт **полного развертывания** бота. Выполняет:
- Сборку fat JAR через `./gradlew shadowJar`
- Создание пакета с JAR, config.properties, rag_docs, embeddings_store
- Отправку всех файлов на сервер через rsync
- Создание скрипта запуска start.sh на сервере
- Генерацию готового systemd service файла
- Настройку прав доступа

### 3. update.sh
Скрипт **быстрого обновления** (только JAR). Выполняет:
- Сборку нового JAR
- Остановку сервиса
- Создание резервной копии старого JAR
- Загрузку нового JAR на сервер
- Запуск сервиса
- **Автоматический откат** к старой версии при ошибке запуска

### 4. telegaga.service.template
Справочный шаблон systemd service файла.

---

## Первоначальная настройка

### Шаг 1: Создание конфигурации деплоя

Скопируйте шаблон конфигурации и заполните своими данными:

```bash
cp deploy.properties.example deploy.properties
nano deploy.properties
```

Пример заполнения `deploy.properties`:

```properties
# SSH Connection
ssh.host=123.45.67.89
ssh.port=22
ssh.user=myuser
# ssh.key=/Users/myuser/.ssh/id_rsa_vps  # опционально

# Deployment Path
deploy.path=/opt/telegaga

# Systemd Service
service.name=telegaga

# Java Path on Server
java.path=/usr/bin/java
```

**ВАЖНО**: Файл `deploy.properties` автоматически исключен из git для безопасности.

### Шаг 2: Выполнение полного развертывания

Запустите скрипт полного развертывания:

```bash
./deploy.sh
```

Скрипт выполнит:
1. Сборку fat JAR
2. Подготовку пакета (JAR + config.properties + rag_docs + embeddings_store)
3. Создание директории на сервере
4. Загрузку всех файлов через rsync
5. Настройку прав доступа
6. Создание start.sh и telegaga.service на сервере

### Шаг 3: Настройка systemd сервиса на сервере

Подключитесь к серверу и установите systemd сервис:

```bash
# Подключение к серверу
ssh myuser@123.45.67.89

# Установка сервиса
sudo cp /opt/telegaga/telegaga.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable telegaga
sudo systemctl start telegaga

# Проверка статуса
sudo systemctl status telegaga
```

### Шаг 4: Проверка работы

Убедитесь, что бот запущен и работает:

```bash
# Просмотр логов в реальном времени
sudo journalctl -u telegaga -f

# Проверка статуса сервиса
sudo systemctl status telegaga

# Последние 100 строк логов
sudo journalctl -u telegaga -n 100
```

---

## Быстрое обновление (только JAR)

После внесения изменений в код используйте скрипт быстрого обновления:

```bash
./update.sh
```

Скрипт автоматически:
1. Соберет новый fat JAR
2. Остановит сервис на сервере
3. Создаст резервную копию старого JAR (telegaga.jar.backup)
4. Загрузит новый JAR
5. Запустит сервис
6. **При ошибке запуска**: автоматически восстановит старую версию

### Процесс обновления:

```bash
# Внесли изменения в код
git add .
git commit -m "Добавлена новая функция"

# Быстрое обновление на сервере
./update.sh
```

---

## Управление сервисом

### Основные команды systemd

```bash
# Запуск сервиса
sudo systemctl start telegaga

# Остановка сервиса
sudo systemctl stop telegaga

# Перезапуск сервиса
sudo systemctl restart telegaga

# Статус сервиса
sudo systemctl status telegaga

# Включить автозапуск
sudo systemctl enable telegaga

# Отключить автозапуск
sudo systemctl disable telegaga
```

### Просмотр логов

```bash
# Логи в реальном времени
sudo journalctl -u telegaga -f

# Последние N строк
sudo journalctl -u telegaga -n 100

# Логи за сегодня
sudo journalctl -u telegaga --since today

# Логи за последний час
sudo journalctl -u telegaga --since "1 hour ago"
```

---

## Ручной запуск (без systemd)

Если вы не хотите использовать systemd, можете запускать бот вручную:

```bash
# Подключение к серверу
ssh myuser@123.45.67.89

# Переход в директорию бота
cd /opt/telegaga

# Запуск
./start.sh

# Или напрямую
java -jar telegaga.jar
```

Для фонового запуска используйте screen или tmux:

```bash
# С использованием screen
screen -S telegaga
./start.sh
# Нажмите Ctrl+A, затем D для отсоединения

# Вернуться к сессии
screen -r telegaga
```

---

## Структура файлов на сервере

После развертывания на сервере будет создана следующая структура:

```
/opt/telegaga/
├── telegaga.jar           # Исполняемый JAR файл
├── telegaga.jar.backup    # Резервная копия (создается при обновлении)
├── config.properties      # Конфигурация бота
├── start.sh               # Скрипт запуска
├── telegaga.service       # Файл systemd сервиса
├── rag_docs/              # Документация для RAG
│   └── readme.md
└── embeddings_store/      # Хранилище эмбеддингов
    └── *.embeddings.json
```

---

## Требования к серверу

### Минимальные требования

- **ОС**: Linux (Ubuntu 20.04+, Debian 10+, CentOS 8+)
- **Java**: OpenJDK 17 или новее
- **RAM**: 512 MB (рекомендуется 1 GB)
- **Disk**: 500 MB свободного места
- **Network**: Доступ к интернету для Telegram API и GigaChat

### Установка Java на сервере (если отсутствует)

#### Ubuntu/Debian:
```bash
sudo apt update
sudo apt install openjdk-17-jre-headless
java -version
```

#### CentOS/RHEL:
```bash
sudo yum install java-17-openjdk-headless
java -version
```

---

## Troubleshooting

### Сервис не запускается

1. Проверьте логи:
```bash
sudo journalctl -u telegaga -n 50
```

2. Проверьте конфигурацию:
```bash
cat /opt/telegaga/config.properties
```

3. Попробуйте запустить вручную:
```bash
cd /opt/telegaga
./start.sh
```

### Проблемы с подключением SSH

1. Проверьте параметры в deploy.properties
2. Убедитесь, что SSH ключ добавлен на сервер:
```bash
ssh-copy-id -i ~/.ssh/id_rsa.pub myuser@server.com
```

3. Проверьте права на ключ:
```bash
chmod 600 ~/.ssh/id_rsa
```

### Ошибка "Permission denied"

Убедитесь, что у пользователя есть права на директорию деплоя:

```bash
# На сервере
sudo chown -R myuser:myuser /opt/telegaga
sudo chmod -R 755 /opt/telegaga
```

### Бот не отвечает в Telegram

1. Проверьте, запущен ли сервис:
```bash
sudo systemctl status telegaga
```

2. Проверьте логи на наличие ошибок:
```bash
sudo journalctl -u telegaga -f
```

3. Проверьте токен в config.properties
4. Убедитесь, что сервер имеет доступ к интернету

---

## Безопасность

### Файлы конфигурации

- **deploy.properties** - содержит SSH данные, **автоматически исключен из git**
- **config.properties** - содержит токены API, **автоматически исключен из git**

### Рекомендации

1. Используйте SSH ключи вместо паролей
2. Ограничьте SSH доступ только для нужных пользователей
3. Настройте firewall на сервере
4. Регулярно обновляйте систему:
```bash
sudo apt update && sudo apt upgrade  # Ubuntu/Debian
```

5. Рассмотрите использование fail2ban для защиты от брутфорса SSH

---

## Автоматизация обновлений

### Создание алиаса для быстрого деплоя

Добавьте в `~/.bashrc` или `~/.zshrc`:

```bash
alias tg-deploy='cd /Users/dmitriikonovalov/Documents/TeleGaGa && ./deploy.sh'
alias tg-update='cd /Users/dmitriikonovalov/Documents/TeleGaGa && ./update.sh'
```

Теперь можно использовать:
```bash
tg-update  # Быстрое обновление
```

### Git hooks для автоматического деплоя

Создайте `.git/hooks/post-commit`:

```bash
#!/bin/bash
# Автоматический деплой после коммита (опционально)

echo "Deploying to VPS..."
/Users/dmitriikonovalov/Documents/TeleGaGa/update.sh
```

Сделайте исполняемым:
```bash
chmod +x .git/hooks/post-commit
```

---

## Мониторинг

### Настройка уведомлений при падении сервиса

Создайте `/etc/systemd/system/telegaga-notify@.service`:

```ini
[Unit]
Description=TeleGaGa Status Notification

[Service]
Type=oneshot
ExecStart=/usr/local/bin/notify-telegaga-failure.sh %i
```

Создайте скрипт `/usr/local/bin/notify-telegaga-failure.sh`:

```bash
#!/bin/bash
# Отправка уведомления при падении сервиса
echo "TeleGaGa service failed!" | mail -s "Alert: TeleGaGa Down" admin@example.com
```

Добавьте в `telegaga.service`:
```ini
[Service]
OnFailure=telegaga-notify@%n.service
```

---

## Резервное копирование

### Создание backup скрипта

```bash
#!/bin/bash
# backup.sh - Резервное копирование данных бота

BACKUP_DIR="/backup/telegaga"
DATE=$(date +%Y%m%d_%H%M%S)

ssh myuser@server.com "tar -czf /tmp/telegaga_${DATE}.tar.gz /opt/telegaga"
scp myuser@server.com:/tmp/telegaga_${DATE}.tar.gz ${BACKUP_DIR}/
ssh myuser@server.com "rm /tmp/telegaga_${DATE}.tar.gz"

echo "Backup completed: telegaga_${DATE}.tar.gz"
```

### Автоматизация через cron

На локальной машине:
```bash
crontab -e

# Ежедневный бэкап в 3:00
0 3 * * * /path/to/backup.sh
```

---

## Дополнительные возможности

### Запуск нескольких инстансов

Для запуска нескольких ботов на одном сервере:

1. Создайте отдельные конфигурации:
```bash
cp deploy.properties deploy.bot2.properties
```

2. Измените пути и имя сервиса:
```properties
deploy.path=/opt/telegaga-bot2
service.name=telegaga-bot2
```

3. Используйте отдельные config.properties для каждого бота

---

## Контакты и поддержка

При возникновении проблем:
1. Проверьте секцию Troubleshooting
2. Изучите логи: `sudo journalctl -u telegaga -n 100`
3. Проверьте конфигурацию deploy.properties и config.properties

---

**Версия**: 1.0
**Дата**: 2026-02-15
**Проект**: TeleGaGa Telegram Bot
