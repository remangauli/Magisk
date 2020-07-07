package com.topjohnwu.magisk.ui.module

import android.Manifest
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.download.RemoteFileService
import com.topjohnwu.magisk.core.model.module.Module
import com.topjohnwu.magisk.core.model.module.Repo
import com.topjohnwu.magisk.core.tasks.RepoUpdater
import com.topjohnwu.magisk.data.database.RepoByNameDao
import com.topjohnwu.magisk.data.database.RepoByUpdatedDao
import com.topjohnwu.magisk.databinding.ComparableRvItem
import com.topjohnwu.magisk.extensions.addOnListChangedCallback
import com.topjohnwu.magisk.extensions.reboot
import com.topjohnwu.magisk.extensions.subscribeK
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject
import com.topjohnwu.magisk.model.entity.recycler.*
import com.topjohnwu.magisk.model.events.InstallExternalModuleEvent
import com.topjohnwu.magisk.model.events.OpenChangelogEvent
import com.topjohnwu.magisk.model.events.SnackbarEvent
import com.topjohnwu.magisk.model.events.dialog.ModuleInstallDialog
import com.topjohnwu.magisk.ui.base.*
import com.topjohnwu.magisk.utils.EndlessRecyclerScrollListener
import com.topjohnwu.magisk.utils.KObservableField
import com.topjohnwu.superuser.internal.UiThreadHandler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.*
import me.tatarka.bindingcollectionadapter2.collections.MergeObservableList
import java.lang.Runnable
import kotlin.math.roundToInt

/*
* The repo fetching behavior should follow these rules:
*
* For the first time the repo list is queried in the app, it should ALWAYS fetch for
* updates. However, this particular fetch should go through RepoUpdater.invoke(false),
* which internally will set ETAGs when doing GET requests to GitHub's API and will
* only update repo DB only if the GitHub API shows that something is changed remotely.
*
* When a user explicitly requests a full DB refresh, it should ALWAYS do a full force
* refresh, which in code can be done with RepoUpdater.invoke(true). This will update
* every single repo's information regardless whether GitHub's API shows if there is
* anything changed or not.
* */

class ModuleViewModel(
    private val repoName: RepoByNameDao,
    private val repoUpdated: RepoByUpdatedDao,
    private val repoUpdater: RepoUpdater
) : BaseViewModel(), Queryable by Queryable.impl(1000) {

    override val queryRunnable = Runnable { query() }

    var query = ""
        @Bindable get
        set(value) {
            if (field == value) return
            field = value
            notifyPropertyChanged(BR.query)
            submitQuery()
            // Yes we do lie about the search being loaded
            searchLoading.value = true
        }

    private var queryJob: Disposable? = null
    val searchLoading = KObservableField(false)
    val itemsSearch = diffListOf<RepoItem>()
    val itemSearchBinding = itemBindingOf<RepoItem> {
        it.bindExtra(BR.viewModel, this)
    }

    private val itemNoneInstalled = TextItem(R.string.no_modules_found)
    private val itemNoneUpdatable = TextItem(R.string.module_update_none)

    private val itemsInstalledHelpers = ObservableArrayList<TextItem>()
    private val itemsUpdatableHelpers = ObservableArrayList<TextItem>()

    private val itemsInstalled = diffListOf<ModuleItem>()
    private val itemsUpdatable = diffListOf<RepoItem.Update>()
    private val itemsRemote = diffListOf<RepoItem.Remote>()

    var isRemoteLoading = false
        @Bindable get
        private set(value) {
            field = value
            notifyPropertyChanged(BR.remoteLoading)
        }

    val adapter = adapterOf<ComparableRvItem<*>>()
    val items = MergeObservableList<ComparableRvItem<*>>()
        .insertItem(InstallModule)
        .insertItem(sectionUpdate)
        .insertList(itemsUpdatableHelpers)
        .insertList(itemsUpdatable)
        .insertItem(sectionActive)
        .insertList(itemsInstalledHelpers)
        .insertList(itemsInstalled)
        .insertItem(sectionRemote)
        .insertList(itemsRemote)!!
    val itemBinding = itemBindingOf<ComparableRvItem<*>> {
        it.bindExtra(BR.viewModel, this)
    }

    companion object {
        private val sectionRemote = SectionTitle(
            R.string.module_section_remote,
            R.string.sorting_order
        )

        private val sectionUpdate = SectionTitle(
            R.string.module_section_pending,
            R.string.module_section_pending_action,
            R.drawable.ic_update_md2
            // enable with implementation of https://github.com/topjohnwu/Magisk/issues/2036
        ).also { it.hasButton = false }

        private val sectionActive = SectionTitle(
            R.string.module_installed,
            R.string.reboot,
            R.drawable.ic_restart
        ).also { it.hasButton = false }

        init {
            updateOrderIcon()
        }

        private fun updateOrderIcon() {
            sectionRemote.icon = when (Config.repoOrder) {
                Config.Value.ORDER_NAME -> R.drawable.ic_order_name
                Config.Value.ORDER_DATE -> R.drawable.ic_order_date
                else -> return
            }
        }
    }

    // ---

    private var refetch = false
    private val dao
        get() = when (Config.repoOrder) {
            Config.Value.ORDER_DATE -> repoUpdated
            Config.Value.ORDER_NAME -> repoName
            else -> throw IllegalArgumentException()
        }

    // ---

    init {
        RemoteFileService.reset()
        RemoteFileService.progressBroadcast.observeForever {
            val (progress, subject) = it ?: return@observeForever
            if (subject !is DownloadSubject.Module) {
                return@observeForever
            }
            update(subject.module, progress.times(100).roundToInt())
        }

        itemsInstalled.addOnListChangedCallback(
            onItemRangeInserted = { _, _, _ -> itemsInstalledHelpers.clear() },
            onItemRangeRemoved = { _, _, _ -> addInstalledEmptyMessage() }
        )
        itemsUpdatable.addOnListChangedCallback(
            onItemRangeInserted = { _, _, _ -> itemsUpdatableHelpers.clear() },
            onItemRangeRemoved = { _, _, _ -> addUpdatableEmptyMessage() }
        )
    }

    // ---

    override fun refresh(): Disposable? {
        if (itemsRemote.isEmpty())
            loadRemote()
        loadInstalled()
        return null
    }

    private suspend fun loadUpdates(installed: List<ModuleItem>) = withContext(Dispatchers.IO) {
        installed
            .mapNotNull { dao.getUpdatableRepoById(it.item.id, it.item.versionCode) }
            .map { RepoItem.Update(it) }
    }

    private suspend fun List<ModuleItem>.loadDetails() = withContext(Dispatchers.IO) {
        onEach {
            launch {
                it.repo = dao.getRepoById(it.item.id)
            }
        }
    }

    private fun loadInstalled() = viewModelScope.launch {
        state = State.LOADING
        val installed = Module.installed().map { ModuleItem(it) }
        val detailLoad = async { installed.loadDetails() }
        val updates = loadUpdates(installed)
        val diff = withContext(Dispatchers.Default) {
            val i = async { itemsInstalled.calculateDiff(installed) }
            val u = async { itemsUpdatable.calculateDiff(updates) }
            awaitAll(i, u)
        }
        detailLoad.await()
        itemsInstalled.update(installed, diff[0])
        itemsUpdatable.update(updates, diff[1])
        addInstalledEmptyMessage()
        addUpdatableEmptyMessage()
        updateActiveState()
        state = State.LOADED
    }

    @Synchronized
    fun loadRemote() {
        // check for existing jobs
        if (isRemoteLoading)
            return
        if (itemsRemote.isEmpty()) {
            EndlessRecyclerScrollListener.ResetState().publish()
        }

        viewModelScope.launch {
            suspend fun loadRemoteDB(offset: Int) = withContext(Dispatchers.IO) {
                dao.getRepos(offset).map { RepoItem.Remote(it) }
            }

            isRemoteLoading = true
            val repos = if (itemsRemote.isEmpty()) {
                repoUpdater(refetch)
                loadRemoteDB(0)
            } else {
                loadRemoteDB(itemsRemote.size)
            }
            isRemoteLoading = false
            refetch = false
            UiThreadHandler.handler.post { itemsRemote.addAll(repos) }
        }
    }

    fun forceRefresh() {
        itemsRemote.clear()
        itemsSearch.clear()
        refetch = true
        refresh()
        submitQuery()
    }

    // ---

    override fun submitQuery() {
        queryHandler.removeCallbacks(queryRunnable)
        queryHandler.postDelayed(queryRunnable, queryDelay)
    }

    private fun queryInternal(query: String, offset: Int): Single<List<RepoItem>> {
        if (query.isBlank()) {
            return Single.just(listOf<RepoItem>())
                .doOnSubscribe { itemsSearch.clear() }
                .subscribeOn(AndroidSchedulers.mainThread())
        }
        return Single.fromCallable { dao.searchRepos(query, offset) }
            .map { it.map { RepoItem.Remote(it) } }
    }

    private fun query(query: String = this.query, offset: Int = 0) {
        queryJob?.dispose()
        queryJob = queryInternal(query, offset)
            .map { it to itemsSearch.calculateDiff(it) }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSuccess { searchLoading.value = false }
            .subscribeK { itemsSearch.update(it.first, it.second) }
    }

    @Synchronized
    fun loadMoreQuery() {
        if (queryJob?.isDisposed == false) return
        queryJob = queryInternal(query, itemsSearch.size)
            .subscribeK { itemsSearch.addAll(it) }
    }

    // ---

    private fun update(repo: Repo, progress: Int) =
        Single.fromCallable { itemsRemote + itemsSearch }
            .map { it.first { it.item.id == repo.id } }
            .subscribeK { it.progress.value = progress }
            .add()

    // ---

    private fun addInstalledEmptyMessage() {
        if (itemsInstalled.isEmpty() && itemsInstalledHelpers.isEmpty()) {
            itemsInstalledHelpers.add(itemNoneInstalled)
        }
    }

    private fun addUpdatableEmptyMessage() {
        if (itemsUpdatable.isEmpty() && itemsUpdatableHelpers.isEmpty()) {
            itemsUpdatableHelpers.add(itemNoneUpdatable)
        }
    }

    // ---

    fun updateActiveState() = Single.fromCallable { itemsInstalled.any { it.isModified } }
        .subscribeK { sectionActive.hasButton = it }
        .add()

    fun sectionPressed(item: SectionTitle) = when (item) {
        sectionActive -> reboot() //TODO add reboot picker, regular reboot is not always preferred
        sectionRemote -> {
            Config.repoOrder = when (Config.repoOrder) {
                Config.Value.ORDER_NAME -> Config.Value.ORDER_DATE
                Config.Value.ORDER_DATE -> Config.Value.ORDER_NAME
                else -> Config.Value.ORDER_NAME
            }
            updateOrderIcon()
            Single.fromCallable { itemsRemote }
                .subscribeK {
                    itemsRemote.removeAll(it)
                    loadRemote()
                }.add()
        }
        else -> Unit
    }

    fun downloadPressed(item: RepoItem) = withPermissions(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ).any { it }.subscribeK(onError = { permissionDenied() }) {
        ModuleInstallDialog(item.item).publish()
    }.add()

    fun installPressed() = withPermissions(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ).any { it }.subscribeK(onError = { permissionDenied() }) {
        InstallExternalModuleEvent().publish()
    }.add()

    fun infoPressed(item: RepoItem) = OpenChangelogEvent(item.item).publish()
    fun infoPressed(item: ModuleItem) {
        OpenChangelogEvent(item.repo ?: return).publish()
    }

    private fun permissionDenied() {
        SnackbarEvent(R.string.module_permission_declined).publish()
    }

}
