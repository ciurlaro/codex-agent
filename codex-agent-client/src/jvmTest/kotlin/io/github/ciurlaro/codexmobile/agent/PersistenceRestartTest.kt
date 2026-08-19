package io.github.ciurlaro.codexmobile.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

class PersistenceRestartTest {
    @Test
    fun aSecondClientRestoresShellTranscriptsAndTurnMetadata(): Unit = runBlocking {
        val root = Files.createTempDirectory("codex-agent-restart-").toFile()
        val shellDirectory = (root.absolutePath + "/shell").toPath()
        val metadataDirectory = (root.absolutePath + "/metadata").toPath()
        val plugin = AgentInvocation.Plugin(
            name = "google-contacts",
            uri = "plugin://google-contacts@openai-curated",
        )

        val writerRuntime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", THREAD_ID) }
                })
                "thread/shellCommand" -> {
                    server.respond(message.id, buildJsonObject {})
                    server.notify("item/started", shellItem(started = true))
                    server.notify("item/completed", shellItem(started = false))
                    server.notify("turn/completed", buildJsonObject {
                        put("threadId", THREAD_ID)
                        putJsonObject("turn") {
                            put("id", SHELL_TURN_ID)
                            put("status", "completed")
                        }
                    })
                }
                "turn/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("turn") { put("id", METADATA_TURN_ID) }
                })
            }
        }
        val writer = persistentClient(writerRuntime, shellDirectory, metadataDirectory)
        try {
            val conversationId = writer.openConversation()
            val shellCompleted = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { writer.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            writer.runShellCommand(conversationId, SHELL_COMMAND)
            shellCompleted.await()
            writer.sendTurn(
                conversationId,
                AgentTurnRequest(
                    prompt = "Find a contact",
                    clientMessageId = CLIENT_MESSAGE_ID,
                    invocations = listOf(plugin),
                ),
                "/workspace",
            )
        } finally {
            writer.close()
        }

        val readerRuntime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/read" -> server.respond(message.id, buildJsonObject {
                    put("thread", persistedThread())
                })
            }
        }
        val reader = persistentClient(readerRuntime, shellDirectory, metadataDirectory)
        try {
            val messages = reader.readConversation(ConversationId(THREAD_ID)).messages

            assertEquals(listOf(AgentMessageRole.USER, AgentMessageRole.ASSISTANT), messages.take(2).map { it.role })
            assertEquals("!$SHELL_COMMAND", messages[0].text)
            assertEquals(SHELL_OUTPUT, messages[1].text)
            assertEquals(0, messages[1].exitCode)
            assertEquals("Find a contact", messages[2].text)
            assertEquals(CLIENT_MESSAGE_ID, messages[2].clientMessageId)
            assertEquals(listOf(plugin), messages[2].invocations)
        } finally {
            reader.close()
            root.deleteRecursively()
        }
    }
}

private fun persistentClient(
    runtime: FakeCodexRuntime,
    shellDirectory: okio.Path,
    metadataDirectory: okio.Path,
): CodexAgentClient = CodexAgentClient(
    runtimeFactory = { runtime },
    requestTimeoutMillis = 1_000,
    shellTranscriptDirectory = shellDirectory,
    turnInputMetadataDirectory = metadataDirectory,
    fileSystem = FileSystem.SYSTEM,
)

private fun shellItem(started: Boolean) = buildJsonObject {
    put("threadId", THREAD_ID)
    put("turnId", SHELL_TURN_ID)
    putJsonObject("item") {
        put("id", SHELL_ITEM_ID)
        put("type", "commandExecution")
        put("source", "userShell")
        put("command", SHELL_COMMAND)
        put("cwd", "/workspace")
        put("commandActions", buildJsonArray {})
        put("status", if (started) "inProgress" else "completed")
        if (!started) {
            put("aggregatedOutput", SHELL_OUTPUT)
            put("exitCode", 0)
        }
    }
}

private fun persistedThread() = thread(
    id = THREAD_ID,
    name = null,
    preview = "",
    updatedAt = 1,
    turns = buildJsonArray {
        add(buildJsonObject {
            put("id", SHELL_TURN_ID)
            put("items", buildJsonArray {})
            put("status", "completed")
        })
        add(buildJsonObject {
            put("id", METADATA_TURN_ID)
            put("items", buildJsonArray {
                add(plainUserMessage("user-meta", CLIENT_MESSAGE_ID, "@google-contacts\n\nFind a contact"))
            })
            put("status", "completed")
        })
    },
)

private const val THREAD_ID = "thread-persisted"
private const val SHELL_TURN_ID = "turn-shell"
private const val METADATA_TURN_ID = "turn-metadata"
private const val SHELL_ITEM_ID = "item-shell"
private const val CLIENT_MESSAGE_ID = "client-metadata"
private const val SHELL_COMMAND = "printf 'saved'"
private const val SHELL_OUTPUT = "saved\n"
