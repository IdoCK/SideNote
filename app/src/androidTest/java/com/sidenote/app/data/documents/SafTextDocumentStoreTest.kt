package com.sidenote.app.data.documents

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTextDocumentStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store by lazy {
        SafTextDocumentStore(context, TestDocumentsProvider.treeUri())
    }

    @Before
    fun setUp() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            TestDocumentsProvider.reset(context.contentResolver)
            TestDocumentsProvider.grantTree(context.contentResolver, context.packageName)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        check(
            SafTreePermission.persist(
                context.contentResolver,
                TestDocumentsProvider.treeUri(),
                SafTreePermission.REQUIRED_FLAGS,
            ) == TreePermissionOutcome.Success,
        )
    }

    @After
    fun tearDown() {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                TestDocumentsProvider.treeUri(),
                SafTreePermission.REQUIRED_FLAGS,
            )
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            TestDocumentsProvider.clear(context.contentResolver)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun exactUtf8RoundTripUsesExactMarkdownFilename() = runTest {
        val markdown = "# 2026-08-27\n\n- [ ] **09:00** שלום, SideNote 👋\n"

        val outcome = store.writeAtomically("2026-08-27.md", null, markdown)

        assertThat(outcome).isEqualTo(WriteOutcome.Success)
        assertThat(store.listNames()).containsExactly("2026-08-27.md")
        assertThat(store.read("2026-08-27.md")).isEqualTo(markdown)
    }

    @Test
    fun permissionDeniedMapsToPermissionLost() = runTest {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            TestDocumentsProvider.denyAccess(context.contentResolver)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }

        val outcome = store.writeAtomically("2026-08-27.md", null, "text")

        assertThat(outcome).isEqualTo(WriteOutcome.Failure(RepositoryError.PermissionLost))
    }

    @Test
    fun missingPersistedGrantMapsToPermissionLost() = runTest {
        context.contentResolver.releasePersistableUriPermission(
            TestDocumentsProvider.treeUri(),
            SafTreePermission.REQUIRED_FLAGS,
        )

        val outcome = store.writeAtomically("2026-08-27.md", null, "text")

        assertThat(outcome).isEqualTo(WriteOutcome.Failure(RepositoryError.PermissionLost))
    }

    @Test
    fun missingPersistedGrantOnListMapsToPermissionLost() = runTest {
        context.contentResolver.releasePersistableUriPermission(
            TestDocumentsProvider.treeUri(),
            SafTreePermission.REQUIRED_FLAGS,
        )

        val failure = runCatching { store.listNames() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DocumentStoreException::class.java)
        assertThat((failure as DocumentStoreException).error)
            .isEqualTo(RepositoryError.PermissionLost)
    }
}
