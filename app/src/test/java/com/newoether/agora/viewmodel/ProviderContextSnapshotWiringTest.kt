package com.newoether.agora.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContextSnapshotWiringTest {
    @Test
    fun productionContextLoadingUsesOneRoomSnapshotAndSharedContextOwners() {
        val root = locateMainSourceRoot()
        val dao = File(
            root,
            "com/newoether/agora/data/local/ChatProviderContextDao.kt",
        ).readText()
        val repository = File(
            root,
            "com/newoether/agora/data/repository/ConversationRepository.kt",
        ).readText()
        val container = File(
            root,
            "com/newoether/agora/di/AppContainer.kt",
        ).readText()
        val loader = File(
            root,
            "com/newoether/agora/viewmodel/DurableSelectedContextLoader.kt",
        ).readText()

        assertTrue("@Transaction must protect the topology snapshot", "@Transaction" in dao)
        assertTrue(
            "the repository must keep topology and payload reads in one Room transaction",
            "withReadSnapshot(block: suspend (ChatDao) -> T): T = readSnapshot(block)" in repository,
        )
        assertTrue(
            "production dependency wiring must supply the database",
            "ConversationRepository(" in container &&
                "chatDao = chatDao" in container &&
                "database = database" in container,
        )
        assertTrue(
            "the loader must enter the repository snapshot before reading topology",
            "conversations.withReadSnapshot { reader ->" in loader,
        )
        assertTrue(
            "topology planning must reuse the canonical protocol assembler",
            "ApiPathAssembler.planTopology(" in loader,
        )
        assertTrue(
            "selected-branch topology must reuse the canonical branch resolver",
            "resolveSelectedPath(" in loader,
        )
        assertFalse(
            "the selected context loader must never materialize the full conversation payload",
            "getMessagesForConversationSnapshot" in loader,
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
