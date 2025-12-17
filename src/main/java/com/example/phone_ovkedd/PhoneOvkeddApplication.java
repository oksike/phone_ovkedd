package com.example.phone_ovkedd;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Основной класс для обработки телефонных номеров и поиска кодов ОКВЭД.
 * Стандарт кодирования: Google Java Style Guide
 * Библиотеки:
 * - Jackson JSON (MIT License) для парсинга JSON
 * - Стандартные библиотеки Java
 */
public class PhoneOvkeddApplication {

    // Константы
    private static final String OKVED_URL = "https://raw.githubusercontent.com/mk0ok/okved/master/okved.json";
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "^[+]?[78]?[-\\s]?[(]?[9]?[0-9]{2}[)]?[-\\s]?[0-9]{3}[-\\s]?[0-9]{2}[-\\s]?[0-9]{2}$"
    );
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\d+");

    /**
     * Нормализует российский мобильный номер телефона
     * @param phoneNumber Входной номер телефона в любом формате
     * @return Нормализованный номер в формате +79XXXXXXXXX или null при ошибке
     * @throws IllegalArgumentException Если номер не может быть нормализован
     */
    public static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Номер телефона не может быть пустым");
        }

        // Удаляем все нецифровые символы, кроме плюса
        String cleaned = phoneNumber.replaceAll("[^\\d+]", "");

        // Извлекаем только цифры
        Matcher digitMatcher = DIGITS_PATTERN.matcher(cleaned);
        if (!digitMatcher.find()) {
            throw new IllegalArgumentException("Номер не содержит цифр: " + phoneNumber);
        }

        String digits = digitMatcher.group();

        // Проверяем длину
        if (digits.length() < 10 || digits.length() > 11) {
            throw new IllegalArgumentException(
                    "Номер должен содержать 10 или 11 цифр (получено " + digits.length() + "): " + phoneNumber
            );
        }

        // Преобразуем в 11-значный формат
        String fullNumber;
        if (digits.length() == 10) {
            // Предполагаем, что это номер без кода страны
            fullNumber = "7" + digits;
        } else {
            // 11 цифр, проверяем код страны
            if (digits.startsWith("8")) {
                fullNumber = "7" + digits.substring(1);
            } else if (digits.startsWith("7")) {
                fullNumber = digits;
            } else {
                throw new IllegalArgumentException("Неверный код страны: " + phoneNumber);
            }
        }

        // Проверяем, что номер начинается с 79 (российский мобильный)
        if (!fullNumber.startsWith("79")) {
            throw new IllegalArgumentException("Номер должен начинаться с 79 (российский мобильный): " + phoneNumber);
        }

        // Проверяем по полному паттерну
        String formatted = "+" + fullNumber;
        if (!PHONE_PATTERN.matcher(formatted).matches()) {
            throw new IllegalArgumentException("Номер не соответствует формату российского мобильного: " + phoneNumber);
        }
        return formatted;
    }

    /**
     * Загружает данные ОКВЭД из удаленного URL
     * @return JsonNode с данными ОКВЭД
     * @throws Exception При ошибках загрузки или парсинга
     */
    public static JsonNode loadOkvedData() throws Exception {
        URL url = new URL(OKVED_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try (InputStream inputStream = connection.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(inputStream);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Рекурсивно собирает все коды ОКВЭД из JSON структуры.
     * @param node Текущий JSON узел
     * @param codes Список для сохранения найденных кодов
     */
    private static void collectOkvedCodes(JsonNode node, List<OkvedCode> codes) {
        if (node == null || !node.isArray()) {
            return;
        }

        for (JsonNode item : node) {
            String code = item.get("code") != null ? item.get("code").asText() : null;
            String name = item.get("name") != null ? item.get("name").asText() : null;

            if (code != null && name != null) {
                codes.add(new OkvedCode(code, name));
            }

            // Рекурсивно обрабатываем вложенные элементы
            if (item.has("items") && item.get("items").isArray()) {
                collectOkvedCodes(item.get("items"), codes);
            }
        }
    }

    /**
     * Находит код ОКВЭД с максимальным совпадением по окончанию номера.
     *
     * @param normalizedPhone Нормализованный номер телефона
     * @param okvedData Данные ОКВЭД
     * @return Результат поиска с информацией о совпадении
     */
    public static OkvedMatchResult findBestOkvedMatch(String normalizedPhone, JsonNode okvedData) {
        // Извлекаем цифры из номера (без +)
        String phoneDigits = normalizedPhone.substring(1);

        // Собираем все коды ОКВЭД
        List<OkvedCode> allCodes = new ArrayList<>();
        collectOkvedCodes(okvedData, allCodes);

        // Сортируем коды по убыванию длины (для приоритета полных совпадений)
        allCodes.sort((a, b) -> Integer.compare(b.code.length(), a.code.length()));

        OkvedCode bestMatch = null;
        int maxMatchLength = 0;

        // Стратегия 1: Ищем совпадения по окончанию
        for (OkvedCode code : allCodes) {
            // Извлекаем цифры из кода ОКВЭД
            String codeDigits = code.code.replaceAll("[^\\d.]", "");

            // Проверяем, заканчивается ли номер на этот код
            if (phoneDigits.endsWith(codeDigits)) {
                int matchLength = codeDigits.length();
                if (matchLength > maxMatchLength) {
                    maxMatchLength = matchLength;
                    bestMatch = code;
                }
            }
        }

        // Стратегия 2 (резервная): Если нет совпадений, берем код по модулю
        if (bestMatch == null) {
            // Преобразуем номер в число и берем остаток от деления на количество кодов
            try {
                long phoneNumberLong = Long.parseLong(phoneDigits.substring(2)); // Без 79
                int index = (int) (phoneNumberLong % allCodes.size());
                bestMatch = allCodes.get(index);
                maxMatchLength = 0; // Нет реального совпадения
            } catch (NumberFormatException e) {
                // Если не удалось преобразовать, берем первый попавшийся код
                bestMatch = allCodes.isEmpty() ?
                        new OkvedCode("00.00", "Код не найден") :
                        allCodes.get(0);
            }
        }

        return new OkvedMatchResult(bestMatch, maxMatchLength);
    }

    /**
     * Основной метод обработки.
     *
     * @param args Аргументы командной строки (первый аргумент - номер телефона)
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Использование: java PhoneOkvedProcessor <номер_телефона>");
            System.out.println("Пример: java PhoneOkvedProcessor \"8 (900) 123-45-67\"");
            return;
        }

        try {
            // Шаг 1: Нормализация номера
            String phoneNumber = args[0];
            String normalized = normalizePhoneNumber(phoneNumber);
            System.out.println(" Нормализованный номер: " + normalized);

            // Шаг 2: Загрузка данных ОКВЭД
            System.out.println(" Загрузка данных ОКВЭД...");
            JsonNode okvedData = loadOkvedData();
            System.out.println(" Данные ОКВЭД загружены");

            // Шаг 3: Поиск наилучшего совпадения
            OkvedMatchResult result = findBestOkvedMatch(normalized, okvedData);

            // Шаг 4: Вывод результатов
            System.out.println("\n Результаты поиска:");
            System.out.println("Номер: " + normalized);
            System.out.println("Код ОКВЭД: " + result.okvedCode.code);
            System.out.println("Название: " + result.okvedCode.name);
            System.out.println("Длина совпадения: " + result.matchLength + " цифр");

            if (result.matchLength == 0) {
                System.out.println(" Использована резервная стратегия (совпадений не найдено)");
            }

        } catch (IllegalArgumentException e) {
            System.err.println(" Ошибка нормализации: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println(" Ошибка: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Класс для хранения кода ОКВЭД и его названия.
     */
    static class OkvedCode {
        String code;
        String name;

        OkvedCode(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /**
     * Класс для хранения результата поиска совпадения.
     */
    static class OkvedMatchResult {
        OkvedCode okvedCode;
        int matchLength;

        OkvedMatchResult(OkvedCode okvedCode, int matchLength) {
            this.okvedCode = okvedCode;
            this.matchLength = matchLength;
        }
    }
}