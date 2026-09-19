package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import exh.source.DelegatedHttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.reflect.KClass

/** Source manager for the local-first fork: the filesystem is the only live source. */
class AndroidSourceManager(
    private val context: Context,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val sourcesMapFlow = MutableStateFlow<Map<Long, Source>>(emptyMap())
    private val sourcePreferences: SourcePreferences by injectLazy()

    override val sources: Flow<List<Source>> = sourcesMapFlow.map { it.values.toList() }

    init {
        scope.launch {
            sourcesMapFlow.value = mapOf(
                LocalSource.ID to LocalSource(
                    context = context,
                    fileSystem = Injekt.get(),
                    coverManager = Injekt.get(),
                    allowHiddenFiles = sourcePreferences.allowLocalSourceHiddenFolders()::get,
                ),
            )
            _isInitialized.value = true
        }
    }

    override fun get(sourceKey: Long): Source? = sourcesMapFlow.value[sourceKey]

    override fun getOrStub(sourceKey: Long): Source =
        sourcesMapFlow.value[sourceKey] ?: StubSource(id = sourceKey, lang = "", name = "")

    override fun getAll(): List<Source> = sourcesMapFlow.value.values.toList()

    override fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()

    override fun getVisibleOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()

    override fun getVisibleSources(): List<Source> = getAll()

    override suspend fun getMergedSources(mangaId: Long): List<Source> = emptyList()

    override fun getStubSources(): List<StubSource> = emptyList()

    /** Compatibility hooks for dormant upstream diagnostics; no delegated sources are registered. */
    fun getDelegatedSources(): List<DelegatedHttpSource> = emptyList()

    companion object {
        val DELEGATED_SOURCES: List<DelegatedSource> = emptyList()
        val currentDelegatedSources: MutableMap<Long, DelegatedSource> = mutableMapOf()

        data class DelegatedSource(
            val sourceName: String,
            val sourceId: Long,
            val newSourceClass: KClass<out DelegatedHttpSource>,
            val originalSourceQualifiedClassName: String = "",
            val factory: Boolean = false,
        )
    }
}
