package mumu.xsy.mumuchat.tools

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import mumu.xsy.mumuchat.ChatViewModel

@RunWith(AndroidJUnit4::class)
class ToolDefinitionsAndroidTest {
    @Test
    fun toolsCatalog_matches_registered_handlers() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = ChatViewModel(app)

        val catalogNames = ToolsCatalog.getToolsDefinition()
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapNotNull { it.getAsJsonObject("function")?.get("name")?.asString }
            .toSet()

        val registeredNames = vm.listRegisteredToolNames()

        val missing = catalogNames - registeredNames
        val extra = registeredNames - catalogNames

        assertTrue("missing handlers for tools: $missing", missing.isEmpty())
        assertTrue("extra registered tools not in catalog: $extra", extra.isEmpty())

        val catalogArr = ToolsCatalog.getToolsDefinition()
        assertEquals(catalogNames.size, catalogArr.countToolNames())
    }
}

private fun JsonArray.countToolNames(): Int {
    var count = 0
    for (e in this) {
        val obj = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val fn = obj.getAsJsonObject("function") ?: continue
        if (!fn.get("name")?.asString.isNullOrBlank()) count++
    }
    return count
}

