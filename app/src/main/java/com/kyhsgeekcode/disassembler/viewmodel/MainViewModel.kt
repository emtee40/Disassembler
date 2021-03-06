package com.kyhsgeekcode.disassembler.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.pollaknet.api.facile.FacileReflector
import at.pollaknet.api.facile.renderer.ILAsmRenderer
import at.pollaknet.api.facile.symtab.symbols.Method
import com.kyhsgeekcode.FileExtensions
import com.kyhsgeekcode.TAG
import com.kyhsgeekcode.disassembler.*
import com.kyhsgeekcode.disassembler.project.ProjectDataStorage
import com.kyhsgeekcode.disassembler.project.ProjectManager
import com.kyhsgeekcode.disassembler.project.models.ProjectModel
import com.kyhsgeekcode.disassembler.project.models.ProjectType
import com.kyhsgeekcode.disassembler.ui.FileDrawerTreeItem
import com.kyhsgeekcode.disassembler.ui.TabData
import com.kyhsgeekcode.disassembler.ui.TabKind
import com.kyhsgeekcode.disassembler.ui.tabs.*
import com.kyhsgeekcode.filechooser.model.FileItem
import com.kyhsgeekcode.filechooser.model.FileItemApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min


sealed class ShowSearchForStringsDialog {
    object NotShown : ShowSearchForStringsDialog()
    object Shown : ShowSearchForStringsDialog()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    sealed class Event {
        object NavigateToSettings : Event()
        data class StartProgress(val dummy: Unit = Unit) : Event()
        data class FinishProgress(val dummy: Unit = Unit) : Event()
        data class AlertError(val text: String) : Event()

        data class ShowSnackBar(val text: String) : Event()

        data class ShowToast(val text: String) : Event()
    }


    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val eventsFlow = eventChannel.receiveAsFlow()

    private val _currentTabIndex = MutableStateFlow(0)
    val currentTabIndex = _currentTabIndex as StateFlow<Int>

    private val _askCopy = MutableStateFlow(false)
    val askCopy = _askCopy as StateFlow<Boolean>

    private val _file = MutableStateFlow(File("/"))
    val file = _file as StateFlow<File>

    private val _nativeFile = MutableStateFlow<File?>(null)
    val nativeFile = _nativeFile as StateFlow<File?>

    private val _projectType = MutableStateFlow(ProjectType.UNKNOWN)
    val projectType = _projectType as StateFlow<String>

    private val _openAsProject = MutableStateFlow(false)
    val openAsProject = _openAsProject as StateFlow<Boolean>

    private val _selectedFilePath = MutableStateFlow("")
    val selectedFilePath = _selectedFilePath as StateFlow<String>

    private val _currentProject = MutableStateFlow<ProjectModel?>(null)
    val currentProject = _currentProject as StateFlow<ProjectModel?>

    private val _fileDrawerRootNode = MutableStateFlow<FileDrawerTreeItem?>(null)
    val fileDrawerRootNode = _fileDrawerRootNode as StateFlow<FileDrawerTreeItem?>

    private val _showSearchForStrings =
        MutableStateFlow<ShowSearchForStringsDialog>(ShowSearchForStringsDialog.NotShown)
    val showSearchForStringsDialog = _showSearchForStrings as StateFlow<ShowSearchForStringsDialog>

    private val _openedTabs =
        MutableStateFlow(listOf(TabData("Overview", TabKind.ProjectOverview)))
    val openedTabs = _openedTabs as StateFlow<List<TabData>>

    private val tabDataMap = HashMap<TabData, PreparedTabData>()

    //  FileDrawerTreeItem(pm.rootFile, 0)

    init {
        viewModelScope.launch {
            currentProject.filterNotNull().collect { pm ->
                _fileDrawerRootNode.value = FileDrawerTreeItem(pm.rootFile, 0)
            }
        }
    }

    fun onSelectIntent(intent: Intent) {
        Timber.d("onActivityResultOk")
        _openAsProject.value = intent.getBooleanExtra("openProject", false)
        val fi = intent.getSerializableExtra("fileItem") as? FileItem
        if (fi != null) {
            onSelectFileItem(fi)
        } else {
            val uri = intent.getParcelableExtra("uri") as Uri?
                ?: intent.getBundleExtra("extras")?.get(Intent.EXTRA_STREAM) as Uri?
                ?: return
            onSelectUri(uri)
        }
    }

    private fun onSelectFileItem(fileItem: FileItem) {
        _file.value = fileItem.file ?: run {
            Logger.e(TAG, "Failed to load fileItem: $fileItem")
            return@onSelectFileItem
        }
        _nativeFile.value = if (fileItem is FileItemApp) {
            fileItem.nativeFile
        } else {
            null
        }
        _projectType.value = fileItemTypeToProjectType(fileItem)
        _askCopy.value = true
    }

    private fun onSelectUri(uri: Uri) {
        if (uri.scheme == "content") {
            try {
                val app = getApplication<Application>()
                app.contentResolver.openInputStream(uri).use { inStream ->
                    val file = app.getExternalFilesDir(null)?.resolve("tmp")?.resolve("openDirect")
                        ?: return
                    file.parentFile.mkdirs()
                    file.outputStream().use { fileOut ->
                        inStream?.copyTo(fileOut)
                    }
                    val project =
                        ProjectManager.newProject(file, ProjectType.UNKNOWN, file.name, true)
                    _selectedFilePath.value = project.sourceFilePath
                    _currentProject.value = project
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    eventChannel.send(Event.FinishProgress())
                    eventChannel.send(Event.AlertError("Failed to create project"))
                }
            }
        }
    }

    fun onCopy(copy: Boolean) {
        _askCopy.value = false
        CoroutineScope(Dispatchers.Main).launch {
            eventChannel.send(Event.StartProgress())
            try {
                val project = withContext(Dispatchers.IO) {
                    onClickCopyDialog(copy)
                }
                _selectedFilePath.value = project.sourceFilePath
                _currentProject.value = project
            } catch (e: Exception) {
                eventChannel.send(Event.AlertError("Failed to create project"))
            }
            eventChannel.send(Event.FinishProgress())
        }
    }

    private fun onClickCopyDialog(
        copy: Boolean
    ): ProjectModel {
        val project =
            ProjectManager.newProject(file.value, projectType.value, file.value.name, copy)
        if (copy) {
            copyNativeDirToProject(nativeFile.value, project)
        }
        return project
    }

    fun onOpenDrawerItem(item: FileDrawerTreeItem) {
        openDrawerItem(item)
    }

    fun openAsHex() {
        val relPath = getCurrentRelPath() ?: return
//        val relPath: String = ProjectManager.getRelPath(absPath)
        val tabData = TabData("$relPath AS HEX", TabKind.Hex(relPath))
        openNewTab(tabData)
    }

    private fun getCurrentRelPath(): String? {
        return when (val tk = openedTabs.value[currentTabIndex.value].tabKind) {
            is TabKind.Binary -> tk.relPath
            is TabKind.AnalysisResult -> null
            is TabKind.Apk -> tk.relPath
            is TabKind.Archive -> tk.relPath
            is TabKind.Dex -> tk.relPath
            is TabKind.DotNet -> tk.relPath
            is TabKind.FoundString -> null
            is TabKind.Hex -> null
            is TabKind.Image -> tk.relPath
            is TabKind.Log -> null
            is TabKind.ProjectOverview -> null
            is TabKind.Text -> null
        }
    }

    private fun openDrawerItem(item: FileDrawerTreeItem) {
        Timber.d("Opening item: ${item.caption}")
        val tabData = createTabData(item)
        openNewTab(tabData)
    }

    @ExperimentalUnsignedTypes
    private fun prepareTabData(tabData: TabData) {
        val data = when (val tabKind = tabData.tabKind) {
            is TabKind.AnalysisResult -> AnalysisTabData(tabKind)
            is TabKind.Apk -> TODO()
            is TabKind.Archive -> TODO()
            is TabKind.Binary -> BinaryTabData(tabKind, viewModelScope)
            is TabKind.Dex -> TODO() // DexTabData(tabKind)
            is TabKind.DotNet ->  TODO()
            is TabKind.Image -> ImageTabData(
                tabKind,
                getApplication<Application>().applicationContext.resources
            )
            is TabKind.ProjectOverview -> PreparedTabData()
            is TabKind.Text -> TextTabData(tabKind)
            is TabKind.FoundString -> StringTabData(tabKind)
            is TabKind.Hex -> HexTabData(tabKind)
            is TabKind.Log -> TODO()
        }
        viewModelScope.launch {
            data.prepare()
        }
        tabDataMap[tabData] = data
    }

    fun <T : PreparedTabData> getTabData(key: TabData): T {
        return (tabDataMap[key] as T)
    }

    fun closeCurrentFile() {
        // free memory and remove from list
        val curIdx = currentTabIndex.value
        if (openedTabs.value.size > 1) {
            _currentTabIndex.value = max(currentTabIndex.value - 1, 0)
            val tabData = openedTabs.value[curIdx]
            tabDataMap.remove(tabData)
            _openedTabs.value = openedTabs.value - tabData
        }
    }

    fun isBinaryTab(): Boolean {
        return openedTabs.value[currentTabIndex.value].tabKind is TabKind.Binary
    }

    fun setCurrentTabByIndex(index: Int) {
        _currentTabIndex.value = index
    }

    fun getCurrentTabData(): PreparedTabData? {
        return tabDataMap[openedTabs.value[currentTabIndex.value]]
    }

    fun searchForStrings() {
        Timber.d("Will be shown strings dialog")
        _showSearchForStrings.value = ShowSearchForStringsDialog.Shown
    }

    fun analyze() {
        val relPath = getCurrentRelPath() ?: return

        val tabData = TabData(
            newCaptionFromCurrent("Analysis"),
            TabKind.AnalysisResult(relPath)
        )
        openNewTab(tabData)
    }

    fun dismissSearchForStringsDialog() {
        _showSearchForStrings.value = ShowSearchForStringsDialog.NotShown
    }

    fun reallySearchForStrings(from: String, to: String) {
        _showSearchForStrings.value = ShowSearchForStringsDialog.NotShown
        val f = from.toIntOrNull() ?: return
        val t = to.toIntOrNull() ?: return
        val relPath = getCurrentRelPath() ?: return

        val tabData = TabData(
            newCaptionFromCurrent("String"),
            TabKind.FoundString(relPath, min(f, t)..max(f, t))
        )
        openNewTab(tabData)
    }

    private fun newCaptionFromCurrent(with: String): String {
        return openedTabs.value[currentTabIndex.value].title.replaceAfter("as ", with)
    }

    private fun openNewTab(tabData: TabData) {
        prepareTabData(tabData)
        val newList = ArrayList<TabData>()
        newList.addAll(openedTabs.value)
        newList.add(tabData)
        _openedTabs.value = newList
    }
}


private fun createTabData(item: FileDrawerTreeItem): TabData {
    var title = "${item.caption} as ${item.type}"
//        val rootPath = ProjectManager.getOriginal("").absolutePath
    if (item.type == FileDrawerTreeItem.DrawerItemType.METHOD) {
        val reflector = (item.tag as Array<*>)[0] as FacileReflector
        val method = (item.tag as Array<*>)[1] as Method
        val renderedStr = ILAsmRenderer(reflector).render(method)
        val key = "${method.owner.name}.${method.name}_${method.methodSignature}"
        ProjectDataStorage.putFileContent(key, renderedStr.encodeToByteArray())
        return TabData(key, TabKind.Text(key))
    }
    val abspath = (item.tag as String)
//        Log.d(TAG, "rootPath:${rootPath}")
    Timber.d("absPath:$abspath")
    val ext = File(abspath).extension.lowercase(Locale.getDefault())
    val relPath: String = ProjectManager.getRelPath(abspath)
//        if (abspath.length > rootPath.length)
//            relPath = abspath.substring(rootPath.length+2)
//        else
//            relPath = ""
    Timber.d("relPath:$relPath")
    val tabkind: TabKind = when (item.type) {
        FileDrawerTreeItem.DrawerItemType.ARCHIVE -> TabKind.Archive(relPath)
        FileDrawerTreeItem.DrawerItemType.APK -> TabKind.Apk(relPath)
        FileDrawerTreeItem.DrawerItemType.NORMAL -> {
            Timber.d("ext:$ext")
            if (FileExtensions.textFileExts.contains(ext)) {
                title = "${item.caption} as Text"
                TabKind.Text(relPath)
            } else {
                val file = File(abspath)
                try {
                    (BitmapFactory.decodeStream(file.inputStream())
                        ?: throw Exception()).recycle()
                    TabKind.Image(relPath)
                } catch (e: Exception) {
                    TabKind.Binary(relPath)
                }
            }
        }
        FileDrawerTreeItem.DrawerItemType.BINARY -> TabKind.Binary(relPath)
        FileDrawerTreeItem.DrawerItemType.PE -> TabKind.Binary(relPath)
        FileDrawerTreeItem.DrawerItemType.PE_IL -> TabKind.DotNet(relPath)
        FileDrawerTreeItem.DrawerItemType.DEX -> TabKind.Dex(relPath)
        /*FileDrawerTreeItem.DrawerItemType.DISASSEMBLY -> TabKind.BinaryDisasm(
            relPath,
            ViewMode.Text
        )*/
        else -> throw Exception()
    }

    return TabData(title, tabkind)
}

fun fileItemTypeToProjectType(fileItem: FileItem): String {
    if (fileItem is FileItemApp)
        return ProjectType.APK
    return ProjectType.UNKNOWN
}

fun copyNativeDirToProject(nativeFile: File?, project: ProjectModel) {
    if (nativeFile != null && nativeFile.exists() && nativeFile.canRead()) {
        val targetFolder = File(project.sourceFilePath + "_libs")
        targetFolder.mkdirs()
        var targetFile = targetFolder.resolve(nativeFile.name)
        var i = 0
        while (targetFile.exists()) {
            targetFile = File(targetFile.absolutePath + "_extracted_$i.so")
            i++
        }
        // FileUtils.copyDirectory(nativeFile, targetFile)
        copyDirectory(nativeFile, targetFile)
    }
}

