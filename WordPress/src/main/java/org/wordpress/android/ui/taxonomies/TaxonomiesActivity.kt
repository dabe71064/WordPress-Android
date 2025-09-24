package org.wordpress.android.ui.taxonomies

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.wordpress.android.R
import org.wordpress.android.ui.ActivityLauncher
import org.wordpress.android.ui.compose.theme.AppThemeM3
import org.wordpress.android.ui.dataview.DataViewScreen
import org.wordpress.android.ui.main.BaseAppCompatActivity
import org.wordpress.android.ui.taxonomies.TaxonomiesViewModel.Companion.displayName
import org.wordpress.android.fluxc.model.TermModel

@AndroidEntryPoint
class TaxonomiesActivity : BaseAppCompatActivity() {
    private val viewModel by viewModels<TaxonomiesViewModel>()

    private lateinit var composeView: ComposeView
    private lateinit var navController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this)
        setContentView(
            composeView.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    this.isForceDarkAllowed = false
                }
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    NavigableContent()
                }
            }
        )

        lifecycleScope.launch {
            viewModel.uiEvent.filterNotNull().collect { event ->
                when (event) {
                    is TaxonomiesViewModel.UiEvent.ShowDeleteConfirmationDialog -> {
                        showDeleteConfirmationDialog(event.term, navController)
                    }

                    is TaxonomiesViewModel.UiEvent.ShowDeleteSuccessDialog -> {
                        showDeleteSuccessDialog()
                    }

                    is TaxonomiesViewModel.UiEvent.ShowToast -> {
                        Toast.makeText(this@TaxonomiesActivity, event.messageRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private enum class TaxonomyScreen {
        List,
        Detail,
        AddTaxonomy
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NavigableContent() {
        navController = rememberNavController()
        val listTitle = stringResource(R.string.taxonomies)
        val titleState = remember { mutableStateOf(listTitle) }
        val showAddTaxonomyButtonState = rememberSaveable { mutableStateOf(true) }

        AppThemeM3 {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(titleState.value) },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (navController.previousBackStackEntry != null) {
                                    navController.navigateUp()
                                } else {
                                    finish()
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (showAddTaxonomyButtonState.value) {
                                IconButton(onClick = {
                                    navController.navigate(route = TaxonomyScreen.AddTaxonomy.name)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.taxonomies_add_taxonomy)
                                    )
                                }
                            }
                        }
                    )
                },
            ) { contentPadding ->
                NavHost(
                    navController = navController,
                    startDestination = TaxonomyScreen.List.name
                ) {
                    composable(route = TaxonomyScreen.List.name) {
                        titleState.value = listTitle
                        showAddTaxonomyButtonState.value = true
                        ShowListScreen(
                            navController,
                            modifier = Modifier.padding(contentPadding)
                        )
                    }

                    composable(route = TaxonomyScreen.Detail.name) {
                        viewModel.getSelectedTerm()?.let { term ->
                            titleState.value = term.displayName()
                            showAddTaxonomyButtonState.value = false
                            ShowTermDetailScreen(
                                term = term,
                                navController = navController,
                                modifier = Modifier.padding(contentPadding)
                            )
                        }
                    }

                    composable(route = TaxonomyScreen.AddTaxonomy.name) {
                        titleState.value = stringResource(R.string.taxonomies_add_taxonomy)
                        showAddTaxonomyButtonState.value = false
                        ShowAddTaxonomyScreen(
                            navController = navController,
                            modifier = Modifier.padding(contentPadding)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ShowListScreen(
        navController: NavHostController,
        modifier: Modifier
    ) {
        DataViewScreen(
            uiState = viewModel.uiState.collectAsState(),
            supportedFilters = viewModel.getSupportedFilters(),
            supportedSorts = viewModel.getSupportedSorts(),
            onRefresh = {
                viewModel.onRefreshData()
            },
            onFetchMore = {
                viewModel.onFetchMoreData()
            },
            onSearchQueryChange = { query ->
                viewModel.onSearchQueryChange(query)
            },
            onItemClick = { item ->
                viewModel.onItemClick(item)
                (item.data as? TermModel)?.let { term ->
                    viewModel.setSelectedTerm(term)
                    navController.currentBackStackEntry?.savedStateHandle?.set(
                        key = KEY_TERM_ID,
                        value = term.remoteTermId
                    )
                    navController.navigate(route = TaxonomyScreen.Detail.name)
                }
            },
            onFilterClick = { filter ->
                viewModel.onFilterClick(filter)
            },
            onSortClick = { sort ->
                viewModel.onSortClick(sort)
            },
            onSortOrderClick = { order ->
                viewModel.onSortOrderClick(order)
            },
            emptyView = viewModel.emptyView,
            modifier = modifier
        )
    }

    @Composable
    private fun ShowTermDetailScreen(
        term: TermModel,
        navController: NavHostController,
        modifier: Modifier
    ) {
        TermDetailScreen(
            term = term,
            onEditClick = { editedTerm ->
                // Handle edit term
                onEditTerm(editedTerm)
            },
            onDeleteClick = { _ ->
                viewModel.onDeleteTermClick(
                    term = term,
                )
            },
            modifier = modifier
        )
    }

    @Composable
    private fun ShowAddTaxonomyScreen(
        navController: NavHostController,
        modifier: Modifier
    ) {
        AddTaxonomyScreen(
            onSubmit = { termData ->
                onSubmitTerm(
                    termData = termData,
                    onSuccess = {
                        navController.navigateUp()
                    }
                )
            },
            onCancel = { navController.navigateUp() },
            modifier = modifier
        )
    }

    private fun showDeleteConfirmationDialog(
        term: TermModel,
        navController: NavHostController
    ) {
        MaterialAlertDialogBuilder(this).also { builder ->
            builder.setTitle(R.string.taxonomies_delete_confirmation_title)
            builder.setMessage(R.string.taxonomies_delete_confirmation_message)
            builder.setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteTermConfirmed(
                    term = term,
                    onSuccess = {
                        navController.navigateUp()
                    }
                )
            }
            builder.setNegativeButton(R.string.cancel, null)
            builder.show()
        }
    }

    private fun showDeleteSuccessDialog() {
        MaterialAlertDialogBuilder(this).also { builder ->
            builder.setTitle(R.string.taxonomies_delete_success_title)
            builder.setMessage(R.string.taxonomies_delete_success_message)
            builder.setPositiveButton(R.string.ok, null)
            builder.show()
        }
    }

    private fun onEditTerm(term: TermModel) {
        // TODO: Implement term editing functionality
        Toast.makeText(this, "Edit term functionality not yet implemented", Toast.LENGTH_SHORT).show()
    }

    private fun onSubmitTerm(termData: Map<String, String>, onSuccess: () -> Unit) {
        // TODO: Implement term creation functionality
        Toast.makeText(this, "Add term functionality not yet implemented", Toast.LENGTH_SHORT).show()
        onSuccess()
    }

    companion object {
        private const val KEY_TERM_ID = "termId"
    }
}
