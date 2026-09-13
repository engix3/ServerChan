package net.himeki.serverchan.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;

/**
 * ConfigLib-based YAML configuration for ServerChan.
 * This configuration is platform-independent and works across all loaders
 * (Fabric, Forge, NeoForge, Spigot).
 */
@Configuration
public class ServerChanYamlConfig {

    @Comment({"", "Enable or disable ServerChan globally",
              "Глобальное включение/отключение Нейроны"})
    public boolean enabled = true;

    @Comment({"", "OpenAI API configuration",
              "Настройки OpenAI API"})
    public OpenAIConfig openai = new OpenAIConfig();

    @Comment({"", "Intention Checker - determines when AI should respond",
              "Проверка намерений - решает, когда ИИ должен отвечать"})
    public IntentionCheckerConfig intention = new IntentionCheckerConfig();

    @Comment({"", "Bot behavior and personality settings",
              "Поведение и характер бота"})
    public BotConfig bot = new BotConfig();

    @Comment({"", "Game event monitoring settings",
              "Настройки отслеживания игровых событий"})
    public EventsConfig events = new EventsConfig();

    @Comment({"", "Watchdog - periodic TPS/ping monitoring with chat warnings",
              "Сторож - периодический мониторинг TPS/пингов с предупреждениями в чат"})
    public WatchdogConfig watchdog = new WatchdogConfig();

    @Comment({"", "Web search via a self-hosted SearXNG instance",
              "Веб-поиск через собственный инстанс SearXNG"})
    public WebSearchConfig webSearch = new WebSearchConfig();

    @Comment({"", "Long-term memory (SQLite) - facts the AI remembers about players",
              "Долговременная память (SQLite) - факты об игроках, которые помнит ИИ"})
    public MemoryConfig memory = new MemoryConfig();

    @Comment({"", "Localization settings",
              "Настройки языка"})
    public LocalizationConfig localization = new LocalizationConfig();

    @Comment({"", "Debug settings",
              "Отладочные настройки"})
    public DebugConfig debug = new DebugConfig();

    /**
     * OpenAI API configuration
     */
    @Configuration
    public static class OpenAIConfig {
        @Comment({"", "API key for OpenAI authentication",
                  "API-ключ для доступа к OpenAI"})
        public String apiKey = "";

        @Comment({"", "Base URL for OpenAI API (can be changed for proxies or compatible services)",
                  "IMPORTANT: Must include /v1 path (e.g., https://api.openai.com/v1)",
                  "Базовый URL OpenAI API (можно поменять для прокси или совместимых сервисов)",
                  "ВАЖНО: путь должен заканчиваться на /v1 (например, https://api.openai.com/v1)"})
        public String baseUrl = "https://api.openai.com/v1";

        @Comment({"", "AI model to use for generating responses",
                  "Модель ИИ для генерации ответов"})
        public String model = "gpt-5.1";

        @Comment({"", "Temperature controls randomness (0=deterministic, 2=very random)",
                  "Температура отвечает за случайность (0=детерминированно, 2=очень случайно)"})
        public double temperature = 1.0;

        @Comment({"", "System prompts for AI behavior",
                  "Системные промпты ИИ"})
        public PromptsConfig prompts = new PromptsConfig();

        @Configuration
        public static class PromptsConfig {
            @Comment({"", "System message that defines AI's behavior and response style",
                      "Системное сообщение, задающее характер и стиль ответов ИИ"})
            public String responseGenerationSystemMessage = ServerChanConfigBase.DEFAULT_RESPONSE_GENERATION_PROMPT;
        }
    }

    /**
     * Intention Checker configuration for AI response decision-making
     */
    @Configuration
    public static class IntentionCheckerConfig {
        @Comment({"", "Enable intention checking to filter when AI should respond",
                  "Включить проверку намерений, чтобы фильтровать, когда ИИ отвечает"})
        public boolean enabled = true;

        @Comment({"", "Use fast path for intention checking (skips some checks for speed)",
                  "Быстрый режим проверки намерений (пропускает часть проверок ради скорости)"})
        public boolean useFastPath = false;

        @Comment({"", "Copy the same API settings as the main ServerChan client (apiKey, baseUrl, model).",
                  "When enabled, the per-checker apiKey/baseUrl/model below are ignored.",
                  "Копировать те же настройки API, что и у основного клиента Нейроны (apiKey, baseUrl, model).",
                  "При включении собственные apiKey/baseUrl/model чекера ниже игнорируются."})
        public boolean useGlobalSettings = true;

        @Comment({"", "Minimum probability threshold for AI to respond (0.0-1.0)",
                  "Минимальный порог вероятности для ответа ИИ (0.0-1.0)"})
        public double responseProbabilityThreshold = 0.5;

        @Comment({"", "Number of recent messages to include in context for intention checking",
                  "Сколько последних сообщений включать в контекст при проверке намерений"})
        public int contextLength = 20;

        @Comment({"", "API key for intention checker (leave empty to use main OpenAI key)",
                  "Ignored when useGlobalSettings is true",
                  "API-ключ для проверки намерений (пусто - используется основной ключ)",
                  "Игнорируется, если включён useGlobalSettings"})
        public String apiKey = "";

        @Comment({"", "Base URL for intention checker API (leave empty to use main URL)",
                  "IMPORTANT: Must include /v1 path if using OpenAI-compatible API",
                  "Ignored when useGlobalSettings is true",
                  "Базовый URL API проверки намерений (пусто - используется основной URL)",
                  "ВАЖНО: для OpenAI-совместимых API путь должен заканчиваться на /v1",
                  "Игнорируется, если включён useGlobalSettings"})
        public String baseUrl = "";

        @Comment({"", "Model for intention checking (usually a faster/cheaper model)",
                  "Ignored when useGlobalSettings is true",
                  "Модель проверки намерений (обычно более быстрая/дешёвая)",
                  "Игнорируется, если включён useGlobalSettings"})
        public String model = "gpt-4o-mini";

        @Comment({"", "Prompts for intention checking system",
                  "Промпты системы проверки намерений"})
        public IntentionPromptsConfig prompts = new IntentionPromptsConfig();

        @Configuration
        public static class IntentionPromptsConfig {
            @Comment({"", "System message for intention checker to determine if AI should respond",
                      "Системное сообщение чекера намерений для решения, должен ли ИИ отвечать"})
            public String systemMessage = ServerChanConfigBase.DEFAULT_INTENTION_CHECKING_PROMPT;
        }
    }

    /**
     * Bot behavior and personality settings
     */
    @Configuration
    public static class BotConfig {
        @Comment({"", "Chat prefix prepended to every bot message. Supports '&' color codes (translated to '§').",
                  "Example: '&d&l[Нейрона]&r '",
                  "Префикс сообщений бота. Поддерживает коды цветов '&' (автоматически переводятся в '§').",
                  "Пример: '&d&l[Нейрона]&r '"})
        public String prefix = "§d§l[Нейрона]§r ";

        @Comment({"", "Name of the main admin / server creator. Server commands are only executed on their request.",
                  "Имя главного админа и создателя сервера. Команды исполняются только по его запросу."})
        public String adminName = "EngiYT";

        @Comment({"", "Color code for bot chat (without §). Examples: b=aqua, e=yellow, a=green, c=red. Kept for backwards compatibility; 'prefix' takes priority.",
                  "Цвет бота (без §) оставлен для совместимости; приоритет у 'prefix'."})
        public String color = "b";

        @Comment({"", "Timezone for time-related functions (e.g., UTC, America/New_York, Asia/Shanghai)",
                  "Часовой пояс (например, UTC, Europe/Moscow, Asia/Shanghai)"})
        public String timeZone = "UTC";

        @Comment({"", "Number of messages to keep in conversation context",
                  "Сколько сообщений держать в контексте диалога"})
        public int contextSize = 20;

        @Comment({"", "Inherit permissions from command source when executing commands",
                  "Наследовать права командующего игрока при исполнении команд"})
        public boolean inheritCmdSourcePermission = true;

        @Comment({""})
        public boolean disableDevEasterEgg = false;
    }

    /**
     * Game event monitoring configuration
     */
    @Configuration
    public static class EventsConfig {
        @Comment({"", "Enable monitoring and responding to game events",
                  "Включить отслеживание и реакцию на игровые события"})
        public boolean enabled = true;

        @Comment({"", "Monitor player join/leave events",
                  "Отслеживать вход/выход игроков"})
        public boolean joinLeaveEvents = true;

        @Comment({"", "Monitor player death events",
                  "Отслеживать смерти игроков"})
        public boolean deathEvents = true;

        @Comment({"", "Monitor player advancement/achievement events",
                  "Отслеживать прогресс/достижения игроков"})
        public boolean advancementEvents = true;

        @Comment({"", "Monitor and process chat messages",
                  "Отслеживать и обрабатывать сообщения чата"})
        public boolean chatEvents = true;
    }

    /**
     * Watchdog configuration (TPS/ping monitoring)
     */
    @Configuration
    public static class WatchdogConfig {
        @Comment({"", "Enable the watchdog: it warns in chat when TPS drops or pings spike",
                  "Включить сторожа: он ругается в чат при просадке TPS или скачках пингов"})
        public boolean enabled = true;

        @Comment({"", "Alert when 1-minute TPS drops below this value",
                  "Тревога, когда минутный TPS падает ниже этого значения"})
        public double tpsThreshold = 15.0;

        @Comment({"", "Alert when the worst player ping exceeds this value (ms)",
                  "Тревога, когда самый плохой пинг превышает это значение (мс)"})
        public int pingThresholdMs = 300;

        @Comment({"", "How often to sample metrics (seconds)",
                  "Как часто снимать метрики (секунды)"})
        public int checkIntervalSeconds = 60;

        @Comment({"", "Minimum time between two alerts of the same kind (seconds)",
                  "Минимальная пауза между двумя тревогами одного типа (секунды)"})
        public int cooldownSeconds = 300;
    }

    /**
     * Web search configuration (SearXNG)
     */
    @Configuration
    public static class WebSearchConfig {
        @Comment({"", "Enable the web_search tool for the AI",
                  "Включить инструмент веб-поиска web_search для ИИ"})
        public boolean enabled = true;

        @Comment({"", "SearXNG search URL template. '{query}' is replaced with the URL-encoded search query.",
                  "IMPORTANT: the instance must allow JSON output (format=json).",
                  "URL-шаблон поиска SearXNG. '{query}' заменяется на закодированный запрос.",
                  "ВАЖНО: инстанс должен разрешать JSON-вывод (format=json)."})
        public String url = "http://31.76.103.40:8888/search?q={query}&format=json&language=ru";

        @Comment({"", "Number of search results returned to the AI (top N)",
                  "Сколько результатов поиска отдавать ИИ (топ N)"})
        public int maxResults = 3;
    }

    /**
     * Long-term memory configuration (SQLite)
     */
    @Configuration
    public static class MemoryConfig {
        @Comment({"", "Enable the SQLite-backed long-term memory and the remember_fact tool",
                  "Включить долговременную память на SQLite и инструмент remember_fact"})
        public boolean enabled = true;
    }

    /**
     * Localization settings
     */
    @Configuration
    public static class LocalizationConfig {
        @Comment({"", "Language/locale code (e.g., en, ru)",
                  "Код языка (например, en, ru)"})
        public String locale = "en";

        @Comment({"", "Automatically detect system locale if locale is not set",
                  "Автоматически определять язык системы, если язык не задан"})
        public boolean autoDetect = true;
    }

    /**
     * Debug settings
     */
    @Configuration
    public static class DebugConfig {
        @Comment({"", "Enable debug file logging",
                  "Включить отладочное логирование в файл"})
        public boolean enableFileLogging = false;
    }

    /**
     * Convert this hierarchical config to the flat base config for backwards compatibility
     */
    public ServerChanConfigBase toBase() {
        ServerChanConfigBase base = new ServerChanConfigBase();

        base.enabled = enabled;

        // Localization
        base.locale = localization.locale;

        // OpenAI settings
        base.openaiApiKey = openai.apiKey;
        base.openaiBaseUrl = openai.baseUrl;
        base.model = openai.model;
        base.temperature = openai.temperature;
        base.responseGenerationSystemMessage = openai.prompts.responseGenerationSystemMessage;

        // Intention checker settings
        base.useIntentionChecker = intention.enabled;
        base.useFastPathIntentionChecker = intention.useFastPath;
        base.responseProbabilityThreshold = intention.responseProbabilityThreshold;
        base.intentionCheckerContextLength = intention.contextLength;
        base.intentionCheckingSystemMessage = intention.prompts.systemMessage;
        if (intention.useGlobalSettings) {
            // Copy the global ServerChan API settings to the intention checker
            base.intentionCheckerApiKey = openai.apiKey;
            base.intentionCheckerBaseUrl = openai.baseUrl;
            base.intentionCheckerModel = openai.model;
        } else {
            base.intentionCheckerApiKey = intention.apiKey;
            base.intentionCheckerBaseUrl = intention.baseUrl;
            base.intentionCheckerModel = intention.model;
        }

        // Bot settings
        base.botPrefix = bot.prefix;
        base.botAdminName = bot.adminName;
        base.botColor = bot.color;
        base.timeZone = bot.timeZone;
        base.contextSize = bot.contextSize;
        base.inheritCmdSourcePermission = bot.inheritCmdSourcePermission;
        base.disableDevEasterEgg = bot.disableDevEasterEgg;

        // Game events
        base.enableGameEvents = events.enabled;
        base.enableJoinLeaveEvents = events.joinLeaveEvents;
        base.enableDeathEvents = events.deathEvents;
        base.enableAdvancementEvents = events.advancementEvents;

        // Watchdog
        base.watchdogEnabled = watchdog.enabled;
        base.watchdogTpsThreshold = watchdog.tpsThreshold;
        base.watchdogPingThresholdMs = watchdog.pingThresholdMs;
        base.watchdogCheckIntervalSeconds = watchdog.checkIntervalSeconds;
        base.watchdogCooldownSeconds = watchdog.cooldownSeconds;

        // Web search
        base.webSearchEnabled = webSearch.enabled;
        base.webSearchUrl = webSearch.url;
        base.webSearchMaxResults = webSearch.maxResults;

        // Long-term memory
        base.memoryEnabled = memory.enabled;

        // Debug
        base.enableDebugFileLogging = debug.enableFileLogging;

        return base;
    }
}
