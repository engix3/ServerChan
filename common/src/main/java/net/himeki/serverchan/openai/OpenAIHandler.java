package net.himeki.serverchan.openai;

import com.google.gson.*;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import net.himeki.serverchan.ServerChanCore;
import net.himeki.serverchan.config.ServerChanConfigBase;
import net.himeki.serverchan.i18n.I18n;
import net.himeki.serverchan.util.MemoryManager;
import net.himeki.serverchan.util.SearXNGClient;
import net.himeki.serverchan.util.ServerMetricsCollector;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class OpenAIHandler {
    private static OpenAIClient openAI;
    private static CircularQueue<MessageWrapper> messageContext;
    private static volatile boolean reloadInProgress = false;
    private static volatile long lastResetTime = 0;
    private static final long RESET_COOLDOWN_MS = 5000; // 5 second cooldown between resets
    private static volatile Future<CompletionResult> currentRequest = null;
    private static final AtomicReference<Exception> lastRequestException = new AtomicReference<>(null);

    // A single-thread executor to ensure requests are handled one at a time
    // We pin the context class loader to the mod's loader so Forge's event transformer
    // can resolve shaded dependencies (Kotlin/OpenAI) without spamming CNF warnings.
    private static volatile ExecutorService completionExecutor = createSingleThreadExecutor("ServerChan-OpenAI");
    // A cached pool for outer async wrappers (CompletableFuture.*) so they don't use the common pool with the wrong CCL.
    private static volatile ExecutorService asyncExecutor = createCachedExecutor("ServerChan-Async");

    private static ThreadFactory modThreadFactory(String prefix) {
        ThreadFactory backing = Executors.defaultThreadFactory();
        return r -> {
            Thread t = backing.newThread(r);
            t.setName(prefix);
            t.setDaemon(true);
            t.setContextClassLoader(OpenAIHandler.class.getClassLoader());
            return t;
        };
    }

    private static ExecutorService createSingleThreadExecutor(String prefix) {
        return Executors.newSingleThreadExecutor(modThreadFactory(prefix));
    }

    private static ExecutorService createCachedExecutor(String prefix) {
        return Executors.newCachedThreadPool(modThreadFactory(prefix));
    }

    public static ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    /**
     * Initialize or reset everything at startup.
     */
    public static void initializeOpenAI() {
        // One concise warning at startup/reload instead of stack traces on every request
        if (!isApiKeyConfigured()) {
            ServerChanCore.LOGGER.warn("OpenAI API key is not configured (openai.apiKey in serverchan.yml) - ServerChan will stay silent until a key is set");
        }

        // Set reload flag to signal ongoing operations to stop
        reloadInProgress = true;

        // Cancel any existing request
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
            currentRequest = null;
        }

    // Clear any pending tasks in the executor to prevent race conditions
    completionExecutor.shutdownNow();
    try {
        // Wait for the old executor to shut down cleanly
        if (!completionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ServerChanCore.LOGGER.warn("Old OpenAI executor did not terminate in time during reload.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ServerChanCore.LOGGER.warn("Interrupted while waiting for executor termination");
    }
    // Recreate the executor
    completionExecutor = createSingleThreadExecutor("ServerChan-OpenAI");

    // Shut down async executor and recreate to avoid thread leaks across reloads
    // Skip if we're running on the async executor to avoid self-interruption during CI/startup.
    boolean onAsyncExecutorThread = Thread.currentThread().getName().startsWith("ServerChan-Async");
    if (!onAsyncExecutorThread) {
        asyncExecutor.shutdownNow();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ServerChanCore.LOGGER.warn("Old async executor did not terminate in time during reload.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ServerChanCore.LOGGER.warn("Interrupted while waiting for async executor termination");
        }
        asyncExecutor = createCachedExecutor("ServerChan-Async");
    } else {
        ServerChanCore.LOGGER.debug("Skipping async executor reset because initializeOpenAI is running on async executor thread");
    }

        resetClient();
        resetMessageContext();

        // Initialize IntentionChecker if enabled
        if (ServerChanCore.CONFIG.useIntentionChecker) {
            IntentionChecker.initialize();
        }

        // Clear the reload flag
        reloadInProgress = false;
    }

    /**
     * Clean up resources (e.g., on shutdown).
     */
    public static void shutdown() {
        completionExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
    }

    /**
     * Resets the OpenAI client with cooldown protection.
     */
    public static synchronized void resetClient() {
        // Check if we recently reset to avoid cascading resets
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastResetTime < RESET_COOLDOWN_MS) {
            ServerChanCore.LOGGER.info("Skipping client reset - cooldown active ({}ms since last reset)",
                currentTime - lastResetTime);
            return;
        }

        // Build the client with custom settings
        openAI = OpenAIOkHttpClient.builder()
                .apiKey(ServerChanCore.CONFIG.openaiApiKey)
                .baseUrl(ServerChanCore.CONFIG.openaiBaseUrl)
                .timeout(Duration.ofSeconds(180))
                .build();

        lastResetTime = currentTime;

        ServerChanCore.LOGGER.info("OpenAI client has been reset with base URL: {}",
            ServerChanCore.CONFIG.openaiBaseUrl);
    }

    public static String getEventResponse(String sender, String input, int permissionLevel) {
        // Game events have no player context - no UUID available for memory/metrics
        return getEventResponse(null, sender, input, permissionLevel);
    }

    public static String getEventResponse(UUID senderUuid, String sender, String input, int permissionLevel) {
        // No API key configured: stay silent instead of spamming 401 stack traces on every event
        if (!isApiKeyConfigured()) {
            return handleMissingApiKey(input);
        }
        recordRequestException(null);
        // Check intention first if enabled (events are marked as game events)
        if (ServerChanCore.CONFIG.useIntentionChecker && ServerChanCore.CONFIG.useFastPathIntentionChecker) {
            // Fast path with early response triggering
            final CompletableFuture<String> responseFuture = new CompletableFuture<>();
            final boolean[] responseStarted = {false};

            IntentionChecker.IntentionResponse intention = IntentionChecker.checkIntentionWithCallback(
                sender, input, true, messageContext.getMessages(),
                earlyResponse -> {
                    // Callback triggered when probability > threshold
                    if (earlyResponse.shouldRespond && !responseStarted[0]) {
                        responseStarted[0] = true;
                        ServerChanCore.LOGGER.info(I18n.get("intention.fastpath.trigger.response"));
                        // Start response generation asynchronously
                        CompletableFuture.runAsync(() -> {
                            try {
                                String result = getResponse(senderUuid, sender, input, permissionLevel);
                                responseFuture.complete(result);
                            } catch (Exception e) {
                                responseFuture.completeExceptionally(e);
                            }
                        }, asyncExecutor);
                    }
                });

            if (intention.isError) {
                ServerChanCore.LOGGER.warn("IntentionChecker failed for event \"{}\" - falling back to model response (reason: {})",
                    input, intention.reason);
            } else if (!intention.shouldRespond) {
                ServerChanCore.LOGGER.info("[NO RESPONSE] Event: \"{}\" | Reason: {} (probability: {}%)",
                    input, intention.reason, (int)(intention.probability * 100));

                // Add the event to context even when not responding
                if (!reloadInProgress) {
                    messageContext.add(MessageWrapper.user(input));
                    messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
                }

                return "<|no_message_this_turn|>";
            }

            // If early response was started, wait for it
            if (responseStarted[0]) {
                try {
                    return responseFuture.get(200, TimeUnit.SECONDS);
                } catch (Exception e) {
                    ServerChanCore.LOGGER.error("Error waiting for early response", e);
                    return I18n.get("handler.error.generic");
                }
            }

            // Fallback if no early response was triggered (shouldn't happen with fast path)
            return getResponse(senderUuid, sender, input, permissionLevel);

        } else if (ServerChanCore.CONFIG.useIntentionChecker) {
            // Normal path without fast path
            IntentionChecker.IntentionResponse intention = IntentionChecker.checkIntentionWithCallback(
                sender, input, true, messageContext.getMessages(), null);
            if (intention.isError) {
                ServerChanCore.LOGGER.warn("IntentionChecker failed for event \"{}\" - falling back to model response (reason: {})",
                    input, intention.reason);
            } else if (!intention.shouldRespond) {
                ServerChanCore.LOGGER.info("[NO RESPONSE] Event: \"{}\" | Reason: {} (probability: {}%)",
                    input, intention.reason, (int)(intention.probability * 100));

                // Add the event to context even when not responding
                if (!reloadInProgress) {
                    messageContext.add(MessageWrapper.user(input));
                    messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
                }

                return "<|no_message_this_turn|>";
            }
        }
        return getResponse(senderUuid, sender, input, permissionLevel);
    }

    public static String getChatResponse(String sender, String input, int permissionLevel) {
        // Backwards-compatible overload for platforms that don't track player UUIDs
        return getChatResponse(null, sender, input, permissionLevel);
    }

    public static String getChatResponse(UUID senderUuid, String sender, String input, int permissionLevel) {
        // No API key configured: stay silent instead of spamming 401 stack traces on every message
        if (!isApiKeyConfigured()) {
            return handleMissingApiKey(input);
        }
        recordRequestException(null);
        // Check intention first if enabled (regular chat messages)
        if (ServerChanCore.CONFIG.useIntentionChecker && ServerChanCore.CONFIG.useFastPathIntentionChecker) {
            // Fast path with early response triggering
            final CompletableFuture<String> responseFuture = new CompletableFuture<>();
            final boolean[] responseStarted = {false};

            IntentionChecker.IntentionResponse intention = IntentionChecker.checkIntentionWithCallback(
                sender, input, false, messageContext.getMessages(),
                earlyResponse -> {
                    // Callback triggered when probability > threshold
                    if (earlyResponse.shouldRespond && !responseStarted[0]) {
                        responseStarted[0] = true;
                        ServerChanCore.LOGGER.info("[FAST PATH] Starting early response generation!");
                        // Start response generation asynchronously
                        CompletableFuture.runAsync(() -> {
                            try {
                                String result = getResponse(senderUuid, sender, input, permissionLevel);
                                responseFuture.complete(result);
                            } catch (Exception e) {
                                responseFuture.completeExceptionally(e);
                            }
                        }, asyncExecutor);
                    }
                });

            if (intention.isError) {
                ServerChanCore.LOGGER.warn("IntentionChecker failed for message \"{}\" - falling back to model response (reason: {})",
                    input, intention.reason);
            } else if (!intention.shouldRespond) {
                ServerChanCore.LOGGER.info("[NO RESPONSE] Message: \"{}\" | Reason: {} (probability: {}%)",
                    input, intention.reason, (int)(intention.probability * 100));

                // Add the message to context even when not responding
                if (!reloadInProgress) {
                    messageContext.add(MessageWrapper.user(input));
                    messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
                }

                return "<|no_message_this_turn|>";
            }

            // If early response was started, wait for it
            if (responseStarted[0]) {
                try {
                    return responseFuture.get(200, TimeUnit.SECONDS);
                } catch (Exception e) {
                    ServerChanCore.LOGGER.error("Error waiting for early response", e);
                    return I18n.get("handler.error.generic");
                }
            }

            // Fallback if no early response was triggered (shouldn't happen with fast path)
            return getResponse(senderUuid, sender, input, permissionLevel);

        } else if (ServerChanCore.CONFIG.useIntentionChecker) {
            // Normal path without fast path
            IntentionChecker.IntentionResponse intention = IntentionChecker.checkIntentionWithCallback(
                sender, input, false, messageContext.getMessages(), null);
            if (intention.isError) {
                ServerChanCore.LOGGER.warn("IntentionChecker failed for message \"{}\" - falling back to model response (reason: {})",
                    input, intention.reason);
            } else if (!intention.shouldRespond) {
                ServerChanCore.LOGGER.info("[NO RESPONSE] Message: \"{}\" | Reason: {} (probability: {}%)",
                    input, intention.reason, (int)(intention.probability * 100));

                // Add the message to context even when not responding
                if (!reloadInProgress) {
                    messageContext.add(MessageWrapper.user(input));
                    messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
                }

                return "<|no_message_this_turn|>";
            }
        }
        return getResponse(senderUuid, sender, input, permissionLevel);
    }

    /**
     * True when an OpenAI API key is configured (non-blank after trimming).
     */
    private static boolean isApiKeyConfigured() {
        String key = ServerChanCore.CONFIG != null ? ServerChanCore.CONFIG.openaiApiKey : null;
        return key != null && !key.trim().isEmpty();
    }

    /**
     * Quiet fallback when no API key is configured: keeps the conversation context
     * consistent (same as the "should not respond" path) and returns the no-message
     * token so the bot stays silent instead of broadcasting errors.
     */
    private static String handleMissingApiKey(String input) {
        if (!reloadInProgress) {
            messageContext.add(MessageWrapper.user(input));
            messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
        }
        return "<|no_message_this_turn|>";
    }

    /** Logs 401 rejections loudly only once per session instead of a stack trace per request. */
    private static final AtomicBoolean unauthorizedWarned = new AtomicBoolean(false);

    private static boolean isUnauthorizedException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof com.openai.errors.UnauthorizedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Main method to get a response from OpenAI.
     * Will queue requests in a single thread, each with a 90s timeout.
     */
    private static String getResponse(UUID senderUuid, String sender, String input,
                                      int permissionLevel) {
        // Always use the response generation system message
        // (IntentionChecker handles the decision logic separately when enabled)
        String systemMessage = ServerChanCore.CONFIG.responseGenerationSystemMessage;

        // Fill in the admin name placeholder from the config
        String adminName = ServerChanCore.CONFIG.botAdminName;
        if (adminName != null && !adminName.trim().isEmpty()) {
            systemMessage = systemMessage.replace(ServerChanConfigBase.ADMIN_NAME_PLACEHOLDER, adminName);
        }

        // Inject long-term memory facts about the current interlocutor into the system context
        if (senderUuid != null && ServerChanCore.CONFIG.memoryEnabled) {
            List<String> facts = MemoryManager.getFacts(senderUuid);
            if (!facts.isEmpty()) {
                systemMessage += "\n\n[Долговременная память об игроке " + sender + "]\n- "
                        + String.join("\n- ", facts);
            }
        }

        // Append dev easter egg prompt if not disabled
        if (!ServerChanCore.CONFIG.disableDevEasterEgg) {
            systemMessage += "\n\n" + I18n.get("prompt.dev.easteregg");
        }

        // Cancel any existing request BEFORE adding new message to context
        if (currentRequest != null && !currentRequest.isDone()) {
            ServerChanCore.LOGGER.info("Cancelling previous OpenAI request to process new one");
            currentRequest.cancel(true);

            // Add a no_message token for the cancelled request to maintain context consistency
            // This prevents the bot from trying to respond to the previous message
            if (!reloadInProgress) {
                messageContext.add(MessageWrapper.assistant("<|no_message_this_turn|>"));
                ServerChanCore.LOGGER.info("Added no_message token for cancelled request");
            }
        }

        // Create a conversation list: system message + user input + all stored context
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(ServerChanCore.CONFIG.model))
                .temperature(ServerChanCore.CONFIG.temperature)
                .addSystemMessage(systemMessage);

        // Only add user message to context if not reloading
        if (!reloadInProgress) {
            messageContext.add(MessageWrapper.user(input));
        }

        // Add all context messages
        for (MessageWrapper msg : messageContext.getMessages()) {
            msg.addToBuilder(paramsBuilder);
        }

        // Define function tools
        addFunctionTools(paramsBuilder);

        // Create a callable that does the heavy lifting
        Callable<CompletionResult> task = () -> {
            try {
                String response = processResponse(senderUuid, sender, paramsBuilder, permissionLevel);
                return new CompletionResult(response, null);
            } catch (Exception e) {
                return new CompletionResult(null, e);
            }
        };

        // Submit the task to the single-thread executor and store the future
        Future<CompletionResult> future = completionExecutor.submit(task);
        currentRequest = future;

        try {
            // Wait up to 200 seconds for the response (slightly more than HTTP timeout)
            CompletionResult result = future.get(200, TimeUnit.SECONDS);

            // If an exception occurred in the worker
            if (result.error != null) {
                recordRequestException(result.error);
                if (isUnauthorizedException(result.error)) {
                    // Wrong/missing key: one concise warning instead of a stack trace per request
                    if (unauthorizedWarned.compareAndSet(false, true)) {
                        ServerChanCore.LOGGER.warn("OpenAI rejected the API key (401 Unauthorized) - check openai.apiKey in serverchan.yml (further 401s are logged quietly)");
                    } else {
                        ServerChanCore.LOGGER.debug("OpenAI request rejected: 401 Unauthorized");
                    }
                } else {
                    ServerChanCore.LOGGER.error("Error in completion task", result.error);
                }
                return I18n.get("handler.error.completion");
            }

            // Possibly the model returned no content
            if (result.response == null) {
                return I18n.get("handler.response.empty");
            }

            return result.response;

        } catch (TimeoutException e) {
            // Timed out -> cancel this request
            future.cancel(true);
            recordRequestException(e);

            // Check if we can reset or if we're in cooldown
            long timeSinceLastReset = System.currentTimeMillis() - lastResetTime;
            if (timeSinceLastReset >= RESET_COOLDOWN_MS) {
                resetClient();
                return I18n.get("handler.error.timeout.reset");
            } else {
                ServerChanCore.LOGGER.warn("Request timed out but reset cooldown active ({}ms remaining)",
                    RESET_COOLDOWN_MS - timeSinceLastReset);
                return I18n.get("handler.error.timeout.recovering");
            }
        } catch (InterruptedException e) {
            // Current thread was interrupted while waiting
            ServerChanCore.LOGGER.error("Interrupted while waiting for completion", e);
            recordRequestException(e);
            return I18n.get("handler.error.interrupted");
        } catch (ExecutionException e) {
            // Something else went wrong
            ServerChanCore.LOGGER.error("Execution error in OpenAI task", e);
            recordRequestException(e);
            return I18n.get("handler.error.execution");
        } finally {
            // Clear the reference if this was the current request
            if (currentRequest == future) {
                currentRequest = null;
            }
        }
    }

    /**
     * The main logic that calls the OpenAI ChatCompletion (function calling etc.).
     * We directly modify the same 'messages' list, so the entire conversation
     * remains in one place.
     */
    private static String processResponse(UUID senderUuid, String sender, ChatCompletionCreateParams.Builder paramsBuilder,
                                          int permissionLevel) {
        boolean functionCallExists = true;
        String finalResponse = null;
        boolean resetContextAfterThisRound = false;

        // 1) Call the model repeatedly until no more function calls.
        while (functionCallExists && !Thread.currentThread().isInterrupted()) {
            ServerChanCore.LOGGER.info(I18n.format("openai.request.starting", ServerChanCore.CONFIG.model));
            ChatCompletion chatCompletion = openAI.chat().completions().create(paramsBuilder.build());
            ChatCompletion.Choice choice = chatCompletion.choices().get(0);
            ChatCompletionMessage message = choice.message();

            // If the assistant made any function calls
            List<ChatCompletionMessageToolCall> toolCalls = message.toolCalls().orElse(Collections.emptyList());
            if (!toolCalls.isEmpty()) {
                // 1.1) Add the assistant's function-call message
                String content = message.content().orElse("Function calls made.");

                // Build the assistant message with tool calls
                MessageWrapper assistantMsg = MessageWrapper.assistant(content, toolCalls);

                assistantMsg.addToBuilder(paramsBuilder);
                // Only add to context if not reloading
                if (!reloadInProgress) {
                    messageContext.add(assistantMsg);
                }

                // 1.2) For each function call, execute it and store the result
                for (int i = 0; i < toolCalls.size(); i++) {
                    ChatCompletionMessageToolCall toolCall = toolCalls.get(i);

                    String toolCallId = "call_" + i; // Default fallback
                    String functionName = "ExecuteMinecraftCommands"; // Default fallback
                    String functionArgsJson = "{}"; // Default empty JSON

                    // Extract function details from the API
                    if (toolCall.function().isPresent()) {
                        try {
                            ChatCompletionMessageFunctionToolCall functionToolCall = toolCall.function().get();
                            toolCallId = functionToolCall.id();

                            // Get the inner function details
                            ChatCompletionMessageFunctionToolCall.Function innerFunction = functionToolCall.function();
                            functionName = innerFunction.name();
                            functionArgsJson = innerFunction.arguments();

                            ServerChanCore.LOGGER.info("Executing function: {} with args: {}", functionName, functionArgsJson);
                        } catch (Exception e) {
                            ServerChanCore.LOGGER.error("Error extracting function details", e);
                        }
                    }

                    String result;
                    String normalizedFunctionName = functionName == null ? "" :
                            functionName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                    switch (normalizedFunctionName) {
                        case "executeminecraftcommands": {
                            List<String> commands = parseCommandsFromJson(functionArgsJson);
                            // Check permission based on config
                            boolean canExecute = !ServerChanCore.CONFIG.inheritCmdSourcePermission || permissionLevel >= 4;
                            result = canExecute
                                    ? executeCommands(sender, commands, permissionLevel)
                                    : I18n.get("handler.command.permission.denied");

                            // IMPORTANT: if the user commands contain "serverchan reset", set a flag
                            if (commands.stream().anyMatch(cmd -> {
                                String cleanCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                                return cleanCmd.equalsIgnoreCase("serverchan reset") || cleanCmd.equalsIgnoreCase("serverchan clear");
                            })) {
                                resetContextAfterThisRound = true;
                            }
                            break;
                        }
                        case "getservermetrics": {
                            result = ServerMetricsCollector.collect(senderUuid, sender);
                            break;
                        }
                        case "websearch": {
                            result = SearXNGClient.search(parseStringArg(functionArgsJson, "query"));
                            break;
                        }
                        case "rememberfact": {
                            result = handleRememberFact(senderUuid, sender, functionArgsJson);
                            break;
                        }
                        default:
                            result = I18n.format("handler.function.unknown", functionName);
                            break;
                    }

                    // Add the tool's result as a new message with the function name
                    MessageWrapper toolMsg = MessageWrapper.tool(result, toolCallId, functionName);
                    toolMsg.addToBuilder(paramsBuilder);
                    // Only add to context if not reloading
                    if (!reloadInProgress) {
                        messageContext.add(toolMsg);
                    }
                }
            } else {
                // No function calls => final text from this model
                functionCallExists = false;
                finalResponse = message.content().orElse("");
            }
        }

        // 2) Once we have the final text, add that to both 'messages' and 'messageContext'
        //    INCLUDING the no_message token so the model knows it already processed this message
        if (isStandaloneNoMessageResponse(finalResponse)) {
            finalResponse = "<|no_message_this_turn|>";
        }

        if (finalResponse != null && !finalResponse.isEmpty()) {
            // Add to persistent context so model knows it processed this
            if (!reloadInProgress) {
                messageContext.add(MessageWrapper.assistant(finalResponse));
            }

            // Optionally: save to file for debugging (only if enabled in config)
            if (ServerChanCore.CONFIG.enableDebugFileLogging) {
                try {
                    saveMessagesToFile(paramsBuilder);
                } catch (Exception e) {
                    ServerChanCore.LOGGER.error("Error saving messages", e);
                }
            }
        }

        if (resetContextAfterThisRound) {
            resetMessageContext();
        }

        return finalResponse;
    }


    /**
     * Save messages to a local JSON file for debugging.
     */
    private static void saveMessagesToFile(ChatCompletionCreateParams.Builder paramsBuilder) {
        String DEBUG_FILE_NAME = "openai_debug_generation.json";
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        JsonObject debugInfo = new JsonObject();
        debugInfo.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        debugInfo.addProperty("phase", "response_generation");
        debugInfo.addProperty("model", ServerChanCore.CONFIG.model);
        debugInfo.addProperty("temperature", ServerChanCore.CONFIG.temperature);
        debugInfo.addProperty("context_size", ServerChanCore.CONFIG.contextSize);

        // Include the message context so the debug file actually reflects the conversation
        JsonArray messages = new JsonArray();
        for (MessageWrapper wrapper : messageContext.getMessages()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", wrapper.getRole().name().toLowerCase(Locale.ROOT));
            msg.addProperty("content", wrapper.getContent());
            messages.add(msg);
        }
        debugInfo.add("messages", messages);

        try (FileWriter writer = new FileWriter(DEBUG_FILE_NAME, false)) {
            writer.write(gson.toJson(debugInfo));
        } catch (IOException e) {
            ServerChanCore.LOGGER.error("Failed to save debug messages to file", e);
        }
    }

    /**
     * A small class to hold either a successful response or an error.
     */
    private static class CompletionResult {
        final String response;
        final Exception error;

        CompletionResult(String response, Exception error) {
            this.response = response;
            this.error = error;
        }
    }

    /**
     * Get the last exception encountered while processing a completion request.
     * Returns null if the last request succeeded.
     */
    public static Exception getLastRequestException() {
        return lastRequestException.get();
    }

    /**
     * Record the last exception encountered by any OpenAI request handler.
     */
    public static void recordRequestException(Exception exception) {
        lastRequestException.set(exception);
    }

    /**
     * Parse a JSON string in a way compatible with all Gson versions.
     * JsonParser.parseString() was added in Gson 2.8.6, but older Minecraft versions use older Gson.
     */
    @SuppressWarnings("deprecation")
    private static JsonElement parseJsonString(String json) {
        return new JsonParser().parse(json);
    }

    /**
     * Parse an array of commands from functionArgsJson.
     */
    private static List<String> parseCommandsFromJson(String functionArgsJson) {
        try {
            JsonElement element = parseJsonString(functionArgsJson);
            JsonObject rootObject = element.getAsJsonObject();
            JsonArray commandsArray = rootObject.getAsJsonArray("commands");
            List<String> commands = new ArrayList<>();
            for (JsonElement commandElement : commandsArray) {
                commands.add(commandElement.getAsString());
            }
            return commands;
        } catch (Exception e) {
            ServerChanCore.LOGGER.error("Failed to parse commands from JSON", e);
            return Collections.emptyList();
        }
    }

    /**
     * Parse a single string argument from a function call's JSON arguments.
     * Returns an empty string when the field is missing or not a primitive.
     */
    private static String parseStringArg(String functionArgsJson, String field) {
        try {
            JsonElement element = parseJsonString(functionArgsJson);
            JsonObject rootObject = element.getAsJsonObject();
            JsonElement value = rootObject.get(field);
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        } catch (Exception e) {
            ServerChanCore.LOGGER.error("Failed to parse '{}' from function arguments JSON", field, e);
        }
        return "";
    }

    /**
     * Execute a list of Minecraft commands as console and return their captured
     * console output to the model. The platform CommandExecutor is responsible
     * for buffering the command feedback (custom buffered CommandSender);
     * here we just assemble a readable ToolResult with status + output.
     */
    private static String executeCommands(String sender, List<String> commands, int permissionLevel) {
        StringBuilder resultBuilder = new StringBuilder();
        for (String command : commands) {
            // Strip leading slash if present
            String cleanCommand = command.startsWith("/") ? command.substring(1) : command;

            try {
                // Use configured permission level or bypass (level 4) based on config
                int effectivePermissionLevel = ServerChanCore.CONFIG.inheritCmdSourcePermission
                    ? permissionLevel
                    : 4;
                String result = ServerChanCore.getCommandExecutor() != null ?
                        ServerChanCore.getCommandExecutor().executeCommand(cleanCommand, effectivePermissionLevel) :
                        "Command executor not initialized";

                // Track command for testing
                ServerChanCore.executedCommandsForTesting.add(cleanCommand);

                resultBuilder
                        .append(I18n.get("handler.command.log.command"))
                        .append("/")
                        .append(cleanCommand)
                        .append("\n")
                        .append(I18n.get("handler.command.log.result"))
                        .append("\n")
                        .append(result == null || result.trim().isEmpty() ? "(no output)" : result)
                        .append("\n\n");

                // Broadcast the command execution message
                if (ServerChanCore.getMessageBroadcaster() != null) {
                    ServerChanCore.getMessageBroadcaster().broadcastMessage(
                            ServerChanCore.formatForChat(
                                    I18n.format("handler.command.broadcast", sender, cleanCommand))
                    );
                }
            } catch (Exception e) {
                resultBuilder
                        .append(I18n.format("handler.command.error", cleanCommand, e.getMessage()))
                        .append("\n\n");
            }
        }
        String fullResult = resultBuilder.toString();
        // The platform executor output may contain legacy '§' color codes - strip them for the LLM
        return truncateForToolResult(fullResult);
    }

    /** Upper bound for a single tool result so huge outputs don't blow up the context window. */
    private static final int MAX_TOOL_RESULT_LENGTH = 4000;

    private static String truncateForToolResult(String result) {
        String cleaned = net.himeki.serverchan.util.ChatFormat.stripColorCodes(result);
        if (cleaned.length() <= MAX_TOOL_RESULT_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_TOOL_RESULT_LENGTH) + "\n... (output truncated, "
                + cleaned.length() + " chars total)";
    }

    /**
     * Handle the remember_fact tool call: store the fact in the SQLite long-term memory
     * for the current conversation partner.
     */
    private static String handleRememberFact(UUID senderUuid, String sender, String functionArgsJson) {
        if (!ServerChanCore.CONFIG.memoryEnabled) {
            return "Long-term memory is disabled in the config";
        }
        if (senderUuid == null) {
            return "Cannot remember facts: player UUID is unknown in this context";
        }
        String key = parseStringArg(functionArgsJson, "key");
        String value = parseStringArg(functionArgsJson, "value");
        if (key.trim().isEmpty()) {
            return "Error: 'key' argument is required";
        }
        boolean saved = MemoryManager.rememberFact(senderUuid, sender, key, value);
        return saved
                ? "Fact saved to long-term memory: '" + key.trim() + "' = '" + value.trim() + "'"
                : "Failed to save fact to long-term memory";
    }

    /**
     * Check if a response indicates the model chose not to reply this turn.
     */
    public static boolean isNoMessageResponse(String response) {
        if (response == null) {
            return false;
        }
        String normalized = response.toLowerCase(Locale.ROOT);
        return normalized.contains("<|no_message_this_turn|>") ||
               normalized.contains("<|no_msg_this_turn|>");
    }

    /**
     * Check for a standalone no-message token (ignoring whitespace/casing).
     */
    private static boolean isStandaloneNoMessageResponse(String response) {
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        return "<|no_message_this_turn|>".equalsIgnoreCase(trimmed) ||
               "<|no_msg_this_turn|>".equalsIgnoreCase(trimmed);
    }


    /**
     * Reset the circular queue storing the chat context.
     */
    public static void resetMessageContext() {
        messageContext = new CircularQueue<>(ServerChanCore.CONFIG.contextSize);
    }

    /**
     * Adds all function tools available to the model.
     */
    private static void addFunctionTools(ChatCompletionCreateParams.Builder paramsBuilder) {
        // Use the class-based approach for function definition
        try {
            paramsBuilder.addTool(ExecuteMinecraftCommands.class);
        } catch (Throwable t) {
            ServerChanCore.LOGGER.error("Failed to register ExecuteMinecraftCommands tool for structured outputs", t);
        }
        addGetServerMetricsTool(paramsBuilder);
        try {
            paramsBuilder.addTool(WebSearchTool.class);
        } catch (Throwable t) {
            ServerChanCore.LOGGER.error("Failed to register WebSearchTool tool for structured outputs", t);
        }
        try {
            paramsBuilder.addTool(RememberFactTool.class);
        } catch (Throwable t) {
            ServerChanCore.LOGGER.error("Failed to register RememberFactTool tool for structured outputs", t);
        }
    }

    /**
     * Registers the get_server_metrics tool with an explicit empty-object schema.
     * The class-based addTool() helper runs local schema validation that rejects
     * schemas with zero properties, which this no-argument tool legitimately has.
     */
    private static void addGetServerMetricsTool(ChatCompletionCreateParams.Builder paramsBuilder) {
        try {
            FunctionParameters parameters = FunctionParameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(new LinkedHashMap<String, Object>()))
                    .putAdditionalProperty("required", JsonValue.from(new ArrayList<String>()))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build();

            ChatCompletionFunctionTool tool = ChatCompletionFunctionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name("get_server_metrics")
                            .description("Get live server metrics: TPS (1m/5m/15m), online players, "
                                    + "JVM memory usage and your ping. Takes no arguments.")
                            .parameters(parameters)
                            .build())
                    .build();

            paramsBuilder.addTool(tool);
        } catch (Throwable t) {
            ServerChanCore.LOGGER.error("Failed to register get_server_metrics tool", t);
        }
    }



    /**
     * A simple circular queue for message context
     */
    public static class CircularQueue<T> {
        private final int maxSize;
        private final LinkedList<T> queue;

        public CircularQueue(int size) {
            this.maxSize = size;
            this.queue = new LinkedList<>();
        }

        public synchronized void add(T message) {
            // If we're at capacity, remove the oldest
            if (queue.size() == maxSize) {
                queue.removeFirst();
            }
            queue.addLast(message);
        }

        public synchronized List<T> getMessages() {
            return new LinkedList<>(queue);
        }

        public synchronized void clear() {
            queue.clear();
        }
    }
}
