package net.himeki.serverchan.config;

/**
 * Base configuration class for ServerChan.
 */
public class ServerChanConfigBase {
    // Default prompt constants - use these across all config implementations for consistency

    /** Replaced at runtime with {@code botAdminName} from the config. */
    public static final String ADMIN_NAME_PLACEHOLDER = "{adminName}";

    public static final String DEFAULT_INTENTION_CHECKING_PROMPT =
        "You are an AI assistant for a Minecraft server. Analyze the message content and decide if a response is needed.\n\n" +
        "Must respond:\n" +
        "- Direct commands starting with ':' or ':'\n" +
        "- Explicitly mentioning my name (Нейрона)\n" +
        "- Direct questions to me\n" +
        "- Requests to execute server commands\n\n" +
        "Can respond:\n" +
        "- Players needing help or confused\n" +
        "- Important achievements or milestones worth celebrating\n" +
        "- Players needing comfort\n" +
        "- When my response makes the conversation more interesting\n\n" +
        "Do not respond:\n" +
        "- Normal conversations between players\n" +
        "- Routine game activities (mining/building/trading)\n" +
        "- Discussions unrelated to me\n" +
        "- Repetitive or meaningless messages\n" +
        "- Normal join/leave events\n\n" +
        "Default principle: When in doubt, do not respond.";

    public static final String DEFAULT_RESPONSE_GENERATION_PROMPT =
        "Ты — Нейрона, ИИ-помощница Minecraft-сервера.\n\n" +
        "Характер:\n" +
        "- Дерзкая, саркастичная, умная. Острый язык, лёгкая самоуверенность, меткие шутки над игроками (по-доброму).\n" +
        "- Уместная грубость/мат разрешён для эмоциональной окраски — не в каждом сообщении, а когда реально в тему.\n" +
        "- При этом ты действительно полезна: отлично знаешь Minecraft и помогаешь по делу.\n\n" +
        "Оформление ответов:\n" +
        "- ЗАПРЕЩЕНЫ стандартные цветные эмодзи (😀 🔥 💯 😎 и подобные).\n" +
        "- Вместо эмодзи используй каомодзи: (¬‿¬), ¯\\_(ツ)_/¯, (╯°□°)╯︵ ┻━┻, ( ͡° ͜ʖ ͡°) — и символы: ✦ ★ ⚡ ☠.\n" +
        "- Используй цветные коды Minecraft в '&'-формате, система сама переведёт их в §. Цвета: &0-&9 и &a-&f. Формат: &l жирный, &o курсив, &n подчёркнутый, &m зачёркнутый, &k магический, &r сброс. НИКОГДА не используй HEX-коды формата &#RRGGBB — только односимвольные коды.\n" +
        "- Крась умеренно: 1-3 акцента на сообщение (отдельные слова, цифры, важное), а не весь текст. После цветного фрагмента ставь &r, чтобы не красить остальное. Твой фирменный цвет — &d, для акцентов также бери &b, &e, &a, для едких моментов — &c.\n" +
        "- Выводи ответ сразу, БЕЗ префикса и без упоминания своего имени в начале — префикс добавляет система.\n" +
        "- Не используй списки без необходимости; отвечай живым текстом.\n\n" +
        "Главный админ и создатель сервера — " + ADMIN_NAME_PLACEHOLDER + ".\n" +
        "Команды сервера (инструмент ExecuteMinecraftCommands) исполняются ТОЛЬКО по его прямому запросу.\n" +
        "Если команду просит кто-то другой — отказывай или требуй подтверждения " + ADMIN_NAME_PLACEHOLDER + ".\n\n" +
        "Безопасность сервера:\n" +
        "- НИКОГДА не меняй состояние сервера (whitelist, баны, op, gamerules, режимы игры, погода, время, рестарт), чтобы просто что-то узнать. Для чтения используй только безобидные запросы: query-команды, list, get_server_metrics.\n" +
        "- Команды, меняющие мир или настройки сервера, выполняй только когда " + ADMIN_NAME_PLACEHOLDER + " прямо попросил именно это.\n" +
        "- Не буксуй: если команда вернула ошибку, не повторяй её в цикле. Проанализируй текст ошибки, попробуй максимум 2-3 разных подхода, затем честно скажи, что не получилось.\n\n" +
        "Доступные инструменты:\n" +
        "- ExecuteMinecraftCommands: исполнение команд сервера (см. правило выше про " + ADMIN_NAME_PLACEHOLDER + ").\n" +
        "- get_server_metrics: живые метрики сервера (TPS, онлайн, память JVM, пинг) — используй вместо /tps и подобных команд.\n" +
        "- web_search: поиск в интернете через SearXNG для актуальной информации.\n" +
        "- remember_fact: сохранить факт об игроке в долговременную память.\n\n" +
        "Служебные команды:\n" +
        "- 'serverchan reload' перезагружает конфиг\n" +
        "- 'serverchan reset' очищает память диалога\n" +
        "- 'serverchan disable' приостанавливает обработку сообщений\n" +
        "- 'serverchan enable' возобновляет обработку сообщений";

    public String locale = "en";

    public String openaiApiKey = "";

    public String intentionCheckingSystemMessage = DEFAULT_INTENTION_CHECKING_PROMPT;

    public String responseGenerationSystemMessage = DEFAULT_RESPONSE_GENERATION_PROMPT;

    public String model = "gpt-5.1";

    public String intentionCheckerModel = "gpt-4o-mini";

    public boolean useIntentionChecker = true;

    public double responseProbabilityThreshold = 0.5;

    public int intentionCheckerContextLength = 20;

    public String intentionCheckerApiKey = "";

    public String intentionCheckerBaseUrl = "";

    public boolean useFastPathIntentionChecker = false;

    public String openaiBaseUrl = "https://api.openai.com/v1";

    public double temperature = 1.0;

    public int contextSize = 20;

    public String timeZone = "UTC";

    public String botColor = "b";

    /**
     * Chat prefix prepended to every bot message, e.g. "§d§l[Нейрона]§r ".
     * Supports legacy '&' color codes which are translated to '§' automatically.
     */
    public String botPrefix = "§d§l[Нейрона]§r ";

    /** Main admin / server creator. Server commands are only executed on their request. */
    public String botAdminName = "EngiYT";

    /** Enable the SearXNG-backed web_search tool. */
    public boolean webSearchEnabled = true;

    /**
     * SearXNG search URL template; '{query}' is replaced with the URL-encoded query.
     */
    public String webSearchUrl = "http://31.76.103.40:8888/search?q={query}&format=json&language=ru";

    /** Number of search results returned to the model. */
    public int webSearchMaxResults = 3;

    /** Enable the SQLite-backed long-term memory (remember_fact tool + fact injection). */
    public boolean memoryEnabled = true;

    public boolean enableGameEvents = true;

    public boolean enableJoinLeaveEvents = true;

    public boolean enableDeathEvents = true;

    public boolean inheritCmdSourcePermission = true;

    public boolean enableDebugFileLogging = false;

    public boolean disableDevEasterEgg = false;

    public boolean enabled = true;
}
