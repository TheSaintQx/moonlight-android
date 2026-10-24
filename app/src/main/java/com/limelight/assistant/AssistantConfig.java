// Путь: app/src/main/java/com/limelight/assistant/AssistantConfig.java
// ЗАМЕНИТЬ файл целиком. Адрес сервера и токен убраны из кода:
// теперь они вводятся в приложении (Настройки → Голосовой помощник).
package com.limelight.assistant;

public final class AssistantConfig {
    public static final int JPEG_QUALITY = 60;
    public static final long PERIOD_MS = 4000L;
    public static final int TARGET_WIDTH = 640;

    private AssistantConfig() {
    }
}
