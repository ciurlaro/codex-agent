package io.github.ciurlaro.codexmobile.agent

import okio.FileSystem
import okio.Path.Companion.toPath
import io.github.ciurlaro.codexmobile.agent.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ChatRuntimeConversationTest {
    @Test
    fun renamesAndDeletesConversationsThroughStableThreadMethods(): Unit = runBlocking {
        var renameParams: JsonObject? = null
        var deleteParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/name/set" -> {
                    renameParams = message.params
                    server.respond(message.id, buildJsonObject {})
                }

                "thread/delete" -> {
                    deleteParams = message.params
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val sessionId = SessionId("thread-history")
            client.renameSession(sessionId, "  Useful name  ")
            client.deleteSession(sessionId)

            assertEquals("thread-history", checkNotNull(renameParams).requiredString("threadId"))
            assertEquals("Useful name", checkNotNull(renameParams).requiredString("name"))
            assertEquals("thread-history", checkNotNull(deleteParams).requiredString("threadId"))
        } finally {
            client.close()
        }
    }

    @Test
    fun runsALeadingBangThroughTheNativeUserShellStream(): Unit = runBlocking {
        val transcriptDirectory = Files.createTempDirectory("shell-transcript-test").toFile()
        var startParams: JsonObject? = null
        var shellParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> {
                    startParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("thread") { put("id", "thread-shell") } },
                    )
                }

                "thread/shellCommand" -> {
                    shellParams = message.params
                    server.respond(message.id, buildJsonObject {})
                    server.notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            putJsonObject("item") {
                                put("command", "printf 'one\\ntwo\\n'")
                                put("commandActions", buildJsonArray {})
                                put("cwd", "/storage/emulated/0/Documents")
                                put("id", "command-shell")
                                put("type", "commandExecution")
                                put("source", "userShell")
                                put("status", "inProgress")
                            }
                        },
                    )
                    server.notify(
                        "item/commandExecution/outputDelta",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            put("itemId", "command-shell")
                            put("delta", "one\ntwo\n")
                        },
                    )
                    server.notify(
                        "item/completed",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            put("turnId", "turn-shell")
                            putJsonObject("item") {
                                put("command", "printf 'one\\ntwo\\n'")
                                put("commandActions", buildJsonArray {})
                                put("cwd", "/storage/emulated/0/Documents")
                                put("id", "command-shell")
                                put("type", "commandExecution")
                                put("source", "userShell")
                                put("status", "completed")
                                put("aggregatedOutput", "one\ntwo\n")
                                put("exitCode", 0)
                            }
                        },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-shell")
                            putJsonObject("turn") {
                                put("id", "turn-shell")
                                put("status", "completed")
                            }
                        },
                    )
                }

                "thread/list" -> server.respond(
                    message.id,
                    page(listOf(thread("thread-shell", null, "", 30)), null),
                )

                "thread/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("thread", thread(
                            id = "thread-shell",
                            name = null,
                            preview = "",
                            updatedAt = 30,
                            turns = buildJsonArray {
                                add(buildJsonObject {
                                    put("id", "turn-shell")
                                    put("items", buildJsonArray {})
                                    put("status", "completed")
                                })
                            },
                        ))
                    },
                )
            }
        }
        val client = CodexAgentClient(
            { process },
            requestTimeoutMillis = 1_000,
            shellTranscriptDirectory = transcriptDirectory.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(workingDirectory = "/storage/emulated/0/Documents"),
            )
            val events = async {
                withTimeout(1_000) {
                    client.events.filter {
                        it is AgentEvent.ShellOutputDelta ||
                            it is AgentEvent.ShellCommandCompleted ||
                            it is AgentEvent.TurnCompleted
                    }.take(3).toList()
                }
            }

            client.runShellCommand(session, "printf 'one\\ntwo\\n'")

            assertEquals("/storage/emulated/0/Documents", checkNotNull(startParams).requiredString("cwd"))
            assertEquals("thread-shell", checkNotNull(shellParams).requiredString("threadId"))
            assertEquals("printf 'one\\ntwo\\n'", checkNotNull(shellParams).requiredString("command"))
            val received = events.await()
            assertEquals("one\ntwo\n", assertIs<AgentEvent.ShellOutputDelta>(received[0]).text)
            assertEquals(0, assertIs<AgentEvent.ShellCommandCompleted>(received[1]).exitCode)
            assertIs<AgentEvent.TurnCompleted>(received[2])

            assertEquals("!printf 'one\\ntwo\\n'", client.listSessions().single().title)
            val history = client.readSession(session)
            assertEquals(listOf(AgentMessageRole.USER, AgentMessageRole.CODEX), history.messages.map { it.role })
            assertEquals("!printf 'one\\ntwo\\n'", history.messages[0].text)
            assertEquals("printf 'one\\ntwo\\n'", history.messages[1].shellCommand)
            assertEquals("one\ntwo\n", history.messages[1].text)
            assertEquals(0, history.messages[1].exitCode)
        } finally {
            client.close()
            transcriptDirectory.deleteRecursively()
        }
    }

}
