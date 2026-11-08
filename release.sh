#!/usr/bin/env bash
# Собирает подписанный релизный APK AuReCut.
#
# Использование:
#   ./release.sh            версия по умолчанию из app/build.gradle.kts
#   ./release.sh 1.2.0      версия 1.2.0 (или 1.2.0-rc1); код версии считается как в GitHub Actions
#
# Необязательные переменные окружения:
#   KEYSTORE_PATH   путь к хранилищу ключей      (по умолчанию ~/aurecut-release.jks)
#   KEY_ALIAS       алиас ключа                  (по умолчанию aurecut)
#   KEYSTORE_PASSWORD, KEY_PASSWORD   если заданы, пароль не спрашивается
#   SKIP_TESTS=1    не запускать модульные тесты
#
# Готовый файл: build/AuReCut-<версия>.apk (или build/AuReCut-release.apk без версии).

set -euo pipefail
cd "$(dirname "$0")"

die() { echo "Ошибка: $*" >&2; exit 1; }

KEYSTORE_PATH="${KEYSTORE_PATH:-$HOME/projects/AuReCutKeys/aurecut-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-aurecut}"

# ---- версия ----
version_args=()
out_name="AuReCut-release.apk"
if [[ $# -gt 1 ]]; then die "ожидается не больше одного аргумента: версия, например 1.2.0"; fi
if [[ $# -eq 1 ]]; then
    version="${1#v}"
    if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.]+)?$ ]]; then
        die "версия '$1' должна иметь вид 1.2.3 или 1.2.3-rc1"
    fi
    major="${BASH_REMATCH[1]}"; minor="${BASH_REMATCH[2]}"; patch="${BASH_REMATCH[3]}"
    if (( minor > 99 || patch > 99 )); then die "минорная версия и патч должны быть от 0 до 99"; fi
    code=$(( major * 10000 + minor * 100 + patch ))
    version_args=("-PappVersionName=$version" "-PappVersionCode=$code")
    out_name="AuReCut-$version.apk"
    echo "Версия $version (код $code)"
fi

# ---- окружение ----
command -v keytool >/dev/null || die "keytool не найден: установите JDK 17 (sudo pacman -S jdk17-openjdk)"
[[ -x ./gradlew ]] || die "рядом со скриптом нет исполняемого gradlew"
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]] && ! grep -qs '^sdk\.dir=' local.properties; then
    die "Android SDK не найден: задайте ANDROID_HOME или создайте local.properties со строкой sdk.dir=/путь/к/Android/Sdk"
fi
[[ -f "$KEYSTORE_PATH" ]] || die "хранилище ключей не найдено: $KEYSTORE_PATH (путь можно задать переменной KEYSTORE_PATH)"

# ---- пароль ----
if [[ -z "${KEYSTORE_PASSWORD:-}" ]]; then
    read -r -s -p "Пароль хранилища ключей ($KEYSTORE_PATH): " KEYSTORE_PASSWORD
    echo
fi
[[ -n "$KEYSTORE_PASSWORD" ]] || die "пароль не введён"
# У хранилища формата PKCS12 пароль ключа совпадает с паролем хранилища.
KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
# Пароли передаются Gradle только через окружение процесса, не через аргументы командной строки.
export KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD

# Быстрая проверка до долгой сборки: пароль и алиас должны подойти.
if ! keytool -list -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass:env KEYSTORE_PASSWORD >/dev/null 2>&1; then
    die "не удалось открыть хранилище: неверный пароль или в нём нет ключа с алиасом '$KEY_ALIAS'"
fi

# ---- сборка ----
tasks=(assembleRelease)
if [[ -z "${SKIP_TESTS:-}" ]]; then tasks=(testDebugUnitTest assembleRelease); fi
./gradlew --no-daemon "${tasks[@]}" "${version_args[@]}"

# ---- результат ----
apk=$(find app/build/outputs/apk/release -name '*.apk' | head -n 1)
[[ -n "$apk" ]] || die "сборка завершилась, но APK не найден в app/build/outputs/apk/release"

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -n 1)}}"
apksigner=$(ls -d "$sdk_root"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)
if [[ -n "$apksigner" ]]; then
    "$apksigner" verify --print-certs "$apk" | head -n 3
else
    echo "apksigner не найден, подпись не проверена"
fi

mkdir -p build
cp "$apk" "build/$out_name"
echo
echo "Готово: build/$out_name"
sha256sum "build/$out_name"
