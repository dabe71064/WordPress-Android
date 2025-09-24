package org.wordpress.android.ui.taxonomies

import android.content.SharedPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.fluxc.store.AccountStore
import org.wordpress.android.fluxc.store.TaxonomyStore
import org.wordpress.android.fluxc.utils.AppLogWrapper
import org.wordpress.android.modules.IO_THREAD
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.dataview.DataViewDropdownItem
import org.wordpress.android.ui.dataview.DataViewFieldType
import org.wordpress.android.ui.dataview.DataViewItem
import org.wordpress.android.ui.dataview.DataViewItemField
import org.wordpress.android.ui.dataview.DataViewItemImage
import org.wordpress.android.ui.dataview.DataViewViewModel
import org.wordpress.android.ui.mysite.SelectedSiteRepository
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.NetworkUtilsWrapper
import org.wordpress.android.fluxc.model.TermModel
import org.wordpress.android.fluxc.model.SiteModel
import uniffi.wp_api.WpApiParamOrder
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class TaxonomiesViewModel @Inject constructor(
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher,
    private val appLogWrapper: AppLogWrapper,
    private val taxonomyStore: TaxonomyStore,
    sharedPrefs: SharedPreferences,
    networkUtilsWrapper: NetworkUtilsWrapper,
    selectedSiteRepository: SelectedSiteRepository,
    accountStore: AccountStore,
    @Named(IO_THREAD) ioDispatcher: CoroutineDispatcher,
) : DataViewViewModel(
    mainDispatcher = mainDispatcher,
    appLogWrapper = appLogWrapper,
    sharedPrefs = sharedPrefs,
    networkUtilsWrapper = networkUtilsWrapper,
    selectedSiteRepository = selectedSiteRepository,
    accountStore = accountStore,
    ioDispatcher = ioDispatcher
) {
    private val _selectedTerm = MutableStateFlow<TermModel?>(null)
    val selectedTerm = _selectedTerm.asStateFlow()

    private var selectedTermModel: TermModel? = null
    private var fetchJob: Job? = null

    override val emptyView = DataViewEmptyView(
        messageRes = R.string.taxonomies_empty,
        imageRes = R.drawable.img_jetpack_empty_state
    )

    sealed class UiEvent {
        data class ShowDeleteConfirmationDialog(val term: TermModel) : UiEvent()
        data object ShowDeleteSuccessDialog : UiEvent()
        data class ShowToast(val messageRes: Int) : UiEvent()
    }

    private val _uiEvent = MutableStateFlow<UiEvent?>(null)
    val uiEvent = _uiEvent

    override fun getSupportedFilters(): List<DataViewDropdownItem> {
        return listOf(
            DataViewDropdownItem(
                id = TaxonomyFilterType.Category.id,
                titleRes = R.string.taxonomies_filter_categories
            ),
            DataViewDropdownItem(
                id = TaxonomyFilterType.Tag.id,
                titleRes = R.string.taxonomies_filter_tags
            )
        )
    }

    override fun getSupportedSorts(): List<DataViewDropdownItem> {
        return listOf(
            DataViewDropdownItem(
                id = TaxonomySortType.Name.id,
                titleRes = R.string.taxonomies_sort_name
            ),
            DataViewDropdownItem(
                id = TaxonomySortType.PostCount.id,
                titleRes = R.string.taxonomies_sort_post_count
            ),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun performNetworkRequest(
        page: Int,
        searchQuery: String,
        filter: DataViewDropdownItem?,
        sortOrder: WpApiParamOrder,
        sortBy: DataViewDropdownItem?,
    ): List<DataViewItem> = withContext(ioDispatcher) {
        try {
            fetchTaxonomies(
                page = page,
                filter = filter,
                sortBy = sortBy,
                sortOrder = sortOrder,
                searchQuery = searchQuery
            )
        } catch (e: Exception) {
            appLogWrapper.e(AppLog.T.MAIN, "Fetch taxonomies failed: $e")
            onError(e.message)
            emptyList()
        }
    }

    private suspend fun fetchTaxonomies(
        page: Int,
        filter: DataViewDropdownItem?,
        sortOrder: WpApiParamOrder,
        sortBy: DataViewDropdownItem?,
        searchQuery: String
    ): List<DataViewItem> = withContext(ioDispatcher) {
        val siteId = siteId()
        if (siteId == 0L) return@withContext emptyList()

        // Create a dummy SiteModel since we need it for the TaxonomyStore
        val site = org.wordpress.android.fluxc.model.SiteModel().apply {
            this.siteId = siteId
        }

        val taxonomyName = when (filter?.id) {
            TaxonomyFilterType.Category.id -> TaxonomyStore.DEFAULT_TAXONOMY_CATEGORY
            TaxonomyFilterType.Tag.id -> TaxonomyStore.DEFAULT_TAXONOMY_TAG
            else -> TaxonomyStore.DEFAULT_TAXONOMY_CATEGORY
        }

        val terms = taxonomyStore.getTermsForSite(site, taxonomyName)

        var filteredTerms = if (searchQuery.isNotEmpty()) {
            terms.filter { term ->
                term.name.contains(searchQuery, ignoreCase = true) ||
                term.description?.contains(searchQuery, ignoreCase = true) == true
            }
        } else {
            terms
        }

        filteredTerms = when (sortBy?.id) {
            TaxonomySortType.Name.id -> {
                if (sortOrder == WpApiParamOrder.ASC) {
                    filteredTerms.sortedBy { it.name.lowercase() }
                } else {
                    filteredTerms.sortedByDescending { it.name.lowercase() }
                }
            }
            TaxonomySortType.PostCount.id -> {
                if (sortOrder == WpApiParamOrder.ASC) {
                    filteredTerms.sortedBy { it.postCount }
                } else {
                    filteredTerms.sortedByDescending { it.postCount }
                }
            }
            else -> filteredTerms.sortedBy { it.name.lowercase() }
        }

        // Simulate pagination
        val startIndex = (page - 1) * PAGE_SIZE
        val endIndex = minOf(startIndex + PAGE_SIZE, filteredTerms.size)
        val paginatedTerms = if (startIndex < filteredTerms.size) {
            filteredTerms.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        appLogWrapper.d(AppLog.T.MAIN, "Fetched ${paginatedTerms.size} terms for taxonomy: $taxonomyName")
        return@withContext paginatedTerms.map { termToDataViewItem(it) }
    }

    private fun termToDataViewItem(term: TermModel): DataViewItem {
        return DataViewItem(
            id = term.remoteTermId,
            image = DataViewItemImage(
                imageUrl = null,
                fallbackImageRes = when (term.taxonomy) {
                    TaxonomyStore.DEFAULT_TAXONOMY_CATEGORY -> R.drawable.ic_folder_multiple_white_24dp
                    TaxonomyStore.DEFAULT_TAXONOMY_TAG -> R.drawable.ic_reader_tags_24dp
                    else -> R.drawable.ic_folder_multiple_white_24dp
                },
            ),
            title = term.name,
            fields = listOf(
                DataViewItemField(
                    value = term.taxonomy.replaceFirstChar { it.uppercase() },
                    valueType = DataViewFieldType.TEXT,
                    weight = .4f,
                ),
                DataViewItemField(
                    value = "${term.postCount} posts",
                    valueType = DataViewFieldType.TEXT,
                    weight = .3f,
                ),
                DataViewItemField(
                    value = term.description ?: "",
                    valueType = DataViewFieldType.TEXT,
                    weight = .3f,
                ),
            ),
            data = term
        )
    }

    fun getTerm(remoteId: Long): TermModel? {
        val item = uiState.value.items.firstOrNull { it.id == remoteId }
        return item?.data as? TermModel
    }

    fun setSelectedTerm(term: TermModel) {
        selectedTermModel = term
        _selectedTerm.value = term
    }

    fun getSelectedTerm(): TermModel? {
        return selectedTermModel
    }

    override fun onItemClick(item: DataViewItem) {
        (item.data as? TermModel)?.let { term ->
            appLogWrapper.d(AppLog.T.MAIN, "Clicked on term ${term.name}")
            setSelectedTerm(term)
        }
    }

    fun onDeleteTermClick(term: TermModel) {
        appLogWrapper.d(AppLog.T.MAIN, "Clicked on delete term ${term.name}")
        _uiEvent.value = UiEvent.ShowDeleteConfirmationDialog(term)
        clearUiEvent()
    }

    fun deleteTermConfirmed(term: TermModel, onSuccess: () -> Unit) {
        launch(ioDispatcher) {
            val result = deleteTerm(term = term)

            withContext(mainDispatcher) {
                if (result.isSuccess) {
                    removeItem(term.remoteTermId)
                    _uiEvent.value = UiEvent.ShowDeleteSuccessDialog
                    onSuccess()
                } else {
                    _uiEvent.value = UiEvent.ShowToast(R.string.taxonomies_delete_failed)
                }
                clearUiEvent()
            }
        }
    }

    private suspend fun deleteTerm(term: TermModel) = runCatching {
        withContext(ioDispatcher) {
            val siteId = siteId()
            if (siteId == 0L) throw IllegalStateException("No site selected")

            // For now, just simulate success
            appLogWrapper.d(AppLog.T.MAIN, "Delete term success (simulated)")
            Result.success(true)
        }
    }

    private fun clearUiEvent() {
        _uiEvent.value = null
    }

    private enum class TaxonomySortType(val id: Long) {
        Name(1L),
        PostCount(2L)
    }

    private enum class TaxonomyFilterType(val id: Long) {
        Category(1L),
        Tag(2L)
    }

    companion object {
        fun TermModel.displayName() = name.ifEmpty { "Unnamed Term" }
    }
}
