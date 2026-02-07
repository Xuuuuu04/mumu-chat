package mumu.xsy.mumuchat.tools

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mumu.xsy.mumuchat.ChatViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolExecutionAndroidTest {
    @Test
    fun local_tools_execute_and_return_json() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = ChatViewModel(app)
        waitUntilSessionsReady(vm)

        vm.updateSettings(
            vm.settings.copy(
                enableLocalTools = true,
                enableSerpSearch = true,
                enableSerpDuckDuckGo = true,
                enablePublicApis = false,
                enableCalendarTools = true,
                enableNotificationTools = true,
                enableFileTools = true
            )
        )

        assertTrue(vm.runTool("list_tools", JsonObject()).looksLikeToolJson())
        assertTrue(vm.runTool("get_settings_summary", JsonObject()).looksLikeToolJson())
        assertTrue(vm.runTool("get_network_status", JsonObject()).looksLikeToolJson())

        assertTrue(vm.runTool("save_memory", JsonObject().apply { addProperty("fact", "测试记忆：工具自检") }).looksLikeToolJson())
        assertTrue(vm.runTool("get_memories", JsonObject()).looksLikeToolJson())
        assertTrue(vm.runTool("search_memories", JsonObject().apply { addProperty("query", "工具自检") }).looksLikeToolJson())

        assertTrue(vm.runTool("save_note", JsonObject().apply {
            addProperty("title", "自检")
            addProperty("content", "工具自检笔记")
        }).looksLikeToolJson())
        assertTrue(vm.runTool("list_notes", JsonObject().apply { addProperty("limit", 5) }).looksLikeToolJson())
        assertTrue(vm.runTool("search_notes", JsonObject().apply { addProperty("query", "自检") }).looksLikeToolJson())

        assertTrue(vm.runTool("calculate", JsonObject().apply { addProperty("code", "1+2+3") }).looksLikeToolJson())

        assertTrue(
            vm.runTool(
                "set_browse_policy",
                JsonObject().apply {
                    add("allowlist", JsonArray().apply { add("example.com") })
                    add("denylist", JsonArray().apply { add("localhost") })
                }
            ).looksLikeToolJson()
        )
        assertTrue(vm.runTool("get_browse_policy", JsonObject()).looksLikeToolJson())

        val deniedCalendar = runToolWithAutoDenies(vm, "calendar_create_event", JsonObject().apply {
            addProperty("title", "测试事件")
            addProperty("start_ms", System.currentTimeMillis() + 60_000L)
            addProperty("end_ms", System.currentTimeMillis() + 120_000L)
        })
        assertTrue(deniedCalendar.looksLikeToolJson())

        val deniedTimer = runToolWithAutoDenies(vm, "notify_set_timer", JsonObject().apply {
            addProperty("seconds", 10)
            addProperty("message", "测试")
        })
        assertTrue(deniedTimer.looksLikeToolJson())

        val deniedExport = runToolWithAutoDenies(vm, "file_export_session", JsonObject())
        assertTrue(deniedExport.looksLikeToolJson())

        val deniedImport = runToolWithAutoDenies(vm, "file_import_session", JsonObject())
        assertTrue(deniedImport.looksLikeToolJson())

        val t2i = vm.runTool("text_to_image", JsonObject().apply { addProperty("prompt", "a cat") })
        assertTrue(t2i.looksLikeToolJson())

        val exa = vm.runTool("exa_search", JsonObject().apply { addProperty("query", "OpenAI") })
        assertTrue(exa.looksLikeToolJson())
    }

    @Test
    fun serp_search_smoke_if_network_available() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = ChatViewModel(app)
        waitUntilSessionsReady(vm)

        vm.updateSettings(
            vm.settings.copy(
                enableSerpSearch = true,
                enableSerpDuckDuckGo = true
            )
        )

        val r = withTimeout(20_000L) {
            vm.runTool(
                "serp_search",
                JsonObject().apply {
                    addProperty("query", "OpenAI")
                    addProperty("engine", "duckduckgo")
                    addProperty("limit", 3)
                    addProperty("page", 1)
                }
            )
        }
        assertTrue(r.looksLikeToolJson())
    }
}

private suspend fun waitUntilSessionsReady(vm: ChatViewModel) {
    withTimeout(5_000L) {
        while (vm.sessions.isEmpty()) delay(50)
    }
}

private fun String.looksLikeToolJson(): Boolean {
    val t = trim()
    return t.startsWith("{") && t.endsWith("}") && t.contains("\"tool\"")
}

private suspend fun runToolWithAutoDenies(vm: ChatViewModel, name: String, args: JsonObject): String {
    return coroutineScope {
        val job = async { vm.runTool(name, args) }
        withTimeout(3_000L) {
            while (true) {
                if (vm.pendingUserApproval != null) {
                    vm.respondToPendingUserApproval(false)
                }
                if (vm.pendingDocumentRequest != null) {
                    vm.onDocumentPicked(null)
                }
                if (job.isCompleted) break
                delay(20)
            }
        }
        job.await()
    }
}
