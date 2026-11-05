# Анализ телефона 2

Отдельная параллельная установка обновлённого Android-приложения (Kotlin). Старое приложение с идентификатором `com.phoneanalyzer` не заменяется и не удаляется.

- Новый идентификатор: `com.phoneanalyzer.next`
- Имя на телефоне: «Анализ телефона 2»
- Первая версия: 1.0.0 (versionCode 1)
- APK: `PhoneAnalyzer-2-v1.0.0.apk`

Дальнейшие обновления должны сохранять `applicationId = "com.phoneanalyzer.next"` и использовать один постоянный ключ подписи. Не устанавливайте debug-validation APK как основную версию: он предназначен только для проверки сборки и может быть подписан временным ключом GitHub runner.

## Постоянная подпись

В репозитории GitHub добавьте Actions secrets:

- `PHONE_KEYSTORE_BASE64` — Base64 содержимое PKCS12 keystore;
- `PHONE_KEYSTORE_PASSWORD` — пароль хранилища;
- `PHONE_KEY_ALIAS` — alias ключа;
- `PHONE_KEY_PASSWORD` — пароль ключа.

Не помещайте keystore или пароли в Git. После настройки секретов workflow создаёт подписанный APK `PhoneAnalyzer-2-v1.0.0.apk` в артефакте `PhoneAnalyzer-2-v1.0.0`. Сохраняйте исходный keystore в безопасном месте.

## Серверный мост

Приложение использует закрытый Supabase Edge Function `phone-analyzer` и существующую регистрацию устройства из Termux bridge проекта contacts. Токен устройства хранится в приложении с шифрованием Android Keystore. Передача отчёта выключена по умолчанию и выполняется только после подтверждения. Сервер хранит отчёты в таблице `phone_analyzer_reports` с RLS и закрытым доступом для клиентских ролей.
