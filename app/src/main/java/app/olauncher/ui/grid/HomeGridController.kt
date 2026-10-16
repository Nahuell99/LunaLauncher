package app.olauncher.ui.grid

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.GridItem
import app.olauncher.data.GridOps
import app.olauncher.data.HomeGrid
import app.olauncher.data.Prefs
import app.olauncher.helper.IconManager
import app.olauncher.helper.MediaNotificationListener
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import app.olauncher.ui.IconPickerDialog
import app.olauncher.ui.settings.SettingsBuilder
import app.olauncher.widgets.CustomWidgetView
import app.olauncher.widgets.WidgetData
import app.olauncher.widgets.WidgetDataProvider
import app.olauncher.widgets.WidgetDef

/**
 * Glue between [HomeGridView] and the rest of the launcher: builds item views (icons,
 * Android widgets, custom widgets), runs edit mode and its menus, binds Android widgets
 * through the official pick / bind / configure flow, and keeps custom widgets current.
 */
class HomeGridController(
    private val grid: HomeGridView,
    private val doneButton: TextView,
    private val prefs: Prefs,
    private val widgetManager: AppWidgetManager,
    private val widgetHost: AppWidgetHost,
    private val actions: Actions,
) : HomeGridView.Listener {

    interface Actions {
        fun launchApp(label: String, pkg: String, cls: String?, user: String)
        fun launchShortcut(label: String, pkg: String, shortcutId: String, user: String)
        fun openFolder(folderId: String)
        fun openClock()
        fun openCalendar()
        fun onSwipe(direction: Int)
        fun pickAppForGrid()
        fun launchWidgetIntent(request: Int, intent: Intent)
        fun requestCalendarPermission()
        fun requestScreenTime()
        fun onEditModeChanged(editing: Boolean)
    }

    // The grid view's own context: valid for as long as any callback on it can run,
    // unlike the fragment's, which detaches before posted work drains.
    private val context: Context get() = grid.context
    private val appContext = grid.context.applicationContext
    private val data = WidgetDataProvider(appContext)
    private val hostViews = HashMap<Int, AppWidgetHostView>()
    private val rebindFailed = HashSet<String>()

    var editing = false
        private set

    init {
        grid.listener = this
        doneButton.setOnClickListener { exitEditMode() }
        ViewCompat.setOnApplyWindowInsetsListener(grid) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + 8.dpToPx(), bars.top + 12.dpToPx(), bars.right + 8.dpToPx(), bars.bottom + 12.dpToPx())
            insets
        }
    }

    // ---------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------

    fun render() {
        if (!prefs.gridEnabled) {
            if (editing) exitEditMode()
            grid.isVisible = false
            stopTicking()
            return
        }
        grid.isVisible = true
        grid.setGridColor(context.getColorFromAttr(R.attr.primaryColor))
        val items = prefs.gridItems.filter(::isAvailable)
        val snapshot by lazy { data.snapshot() }
        grid.setItems(prefs.gridColumns, prefs.gridRows, items) { item, old -> viewFor(item, old) { snapshot } }
        grid.post { updateWidgetSizes() }
        if (items.any { it.type == GridItem.TYPE_CUSTOM_WIDGET && defOf(it)?.uses("screentime") == true }) actions.requestScreenTime()
        if (ticking) registerMedia() else if (hasCustomWidgets()) startTicking()
        if (resumeEditing) {
            resumeEditing = false
            enterEditMode(announce = false)
        }
    }

    private fun isAvailable(item: GridItem): Boolean = when (item.type) {
        GridItem.TYPE_APP, GridItem.TYPE_SHORTCUT -> isPackageInstalled(context, item.pkg, item.user)
        GridItem.TYPE_FOLDER -> prefs.getFolder(item.folderId) != null
        else -> true
    }

    private fun defOf(item: GridItem): WidgetDef? = WidgetDef.Library.find(context, prefs, item.widgetDefId)

    private fun viewFor(item: GridItem, old: View?, snapshot: () -> WidgetData): View = when (item.type) {
        GridItem.TYPE_APP_WIDGET -> appWidgetView(item, old)
        GridItem.TYPE_CUSTOM_WIDGET -> customWidgetView(item, old, snapshot())
        else -> ((old as? GridIconView) ?: GridIconView(context)).also { bindIcon(it, item) }
    }

    private fun bindIcon(view: GridIconView, item: GridItem) {
        val ctx = context
        val tint = ctx.getColorFromAttr(R.attr.primaryColor)
        view.iconScale = prefs.gridIconScale.coerceIn(0.3f, 2.5f)
        view.showLabel = prefs.gridShowLabels
        when (item.type) {
            GridItem.TYPE_FOLDER -> {
                val folder = prefs.getFolder(item.folderId)
                view.bind(ContextCompat.getDrawable(ctx, R.drawable.ic_sc_folder), folder?.name ?: item.label, tint)
            }

            GridItem.TYPE_SHORTCUT -> {
                val user = getUserHandleFromString(ctx, item.user)
                val icon = IconManager.getShortcutIcon(ctx, item.pkg, item.shortcutId, user)
                    ?: IconManager.getAppIcon(ctx, item.pkg, user, tint)
                view.bind(icon, prefs.getShortcutRenameLabel(item.pkg, item.user, item.shortcutId).ifEmpty { item.label }, null)
            }

            else -> {
                val user = getUserHandleFromString(ctx, item.user)
                val icon = IconManager.getAppIcon(ctx, item.pkg, user, tint, item.cls.ifEmpty { null })
                view.bind(icon, prefs.getAppRenameLabel(item.pkg).ifEmpty { item.label }, null)
            }
        }
        view.requestLayout()
    }

    private fun appWidgetView(item: GridItem, old: View?): View {
        var id = item.appWidgetId
        var info = if (id != -1) widgetManager.getAppWidgetInfo(id) else null
        if (info == null && item.provider.isNotEmpty() && item.id !in rebindFailed) {
            // Restored from a backup or the provider was updated: try to re-bind silently.
            if (id != -1) runCatching { widgetHost.deleteAppWidgetId(id) }
            val component = ComponentName.unflattenFromString(item.provider)
            val newId = widgetHost.allocateAppWidgetId()
            val bound = component != null && runCatching { widgetManager.bindAppWidgetIdIfAllowed(newId, component) }.getOrDefault(false)
            if (bound) {
                id = newId
                info = widgetManager.getAppWidgetInfo(newId)
                GridOps.update(prefs, item.copy(appWidgetId = newId))
            } else {
                runCatching { widgetHost.deleteAppWidgetId(newId) }
                rebindFailed += item.id
            }
        }
        if (info == null) return placeholder(item, old)
        hostViews[id]?.let { return it }
        return try {
            widgetHost.createView(appContext, id, info).also { hostViews[id] = it }
        } catch (e: Exception) {
            placeholder(item, old)
        }
    }

    private fun placeholder(item: GridItem, old: View?): View =
        ((old as? TextView)?.takeIf { it.tag == PLACEHOLDER } ?: TextView(context).apply {
            tag = PLACEHOLDER
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setBackgroundResource(R.drawable.rounded_rect_shade_color_glass)
        }).apply {
            text = context.getString(R.string.widget_rebind)
            setOnClickListener { startRebind(item) }
        }

    private fun customWidgetView(item: GridItem, old: View?, snapshot: WidgetData): View {
        val def = defOf(item) ?: return placeholder(item, old)
        val view = (old as? CustomWidgetView)?.takeIf { it.def.id == def.id }
            ?: CustomWidgetView(context, def, item.data, onTap = { tap -> onCustomTap(item.id, tap, def) }, onMediaAction = ::mediaAction)
        view.updateItemData(item.data)
        view.refresh(snapshot)
        return view
    }

    private fun updateWidgetSizes() {
        val density = context.resources.displayMetrics.density
        for (item in prefs.gridItems) {
            if (item.type != GridItem.TYPE_APP_WIDGET) continue
            val view = hostViews[item.appWidgetId] ?: continue
            val w = grid.cellWidth * item.spanX / density
            val h = grid.cellHeight * item.spanY / density
            if (w <= 0f || h <= 0f) continue
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
                else @Suppress("DEPRECATION") view.updateAppWidgetSize(Bundle(), w.toInt(), h.toInt(), w.toInt(), h.toInt())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Live data for custom widgets
    // ---------------------------------------------------------------------------------

    private var ticking = false
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) data.invalidateEvents()
            refreshWidgets()
        }
    }

    private fun hasCustomWidgets() = prefs.gridEnabled && prefs.gridItems.any { it.type == GridItem.TYPE_CUSTOM_WIDGET }

    fun startTicking() {
        if (ticking || !hasCustomWidgets()) return
        ticking = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, tickReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registerMedia()
        refreshWidgets()
    }

    fun stopTicking() {
        if (!ticking) return
        ticking = false
        runCatching { appContext.unregisterReceiver(tickReceiver) }
        unregisterMedia()
    }

    fun refreshWidgets() {
        if (!prefs.gridEnabled) return
        val snapshot = data.snapshot()
        for (item in prefs.gridItems) {
            if (item.type != GridItem.TYPE_CUSTOM_WIDGET) continue
            (grid.viewFor(item.id) as? CustomWidgetView)?.refresh(snapshot)
        }
    }

    fun setScreenTime(value: String) {
        data.screenTime = value
        refreshWidgets()
    }

    fun onCalendarPermissionResult() {
        data.invalidateEvents()
        refreshWidgets()
    }

    private var sessionManager: MediaSessionManager? = null
    private var watched: MediaController? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        watch(data.activeController())
        refreshWidgets()
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshWidgets()
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = refreshWidgets()
    }

    private fun registerMedia() {
        if (sessionManager != null || !MediaNotificationListener.isEnabled(appContext)) return
        val hasMedia = prefs.gridItems.any { it.type == GridItem.TYPE_CUSTOM_WIDGET && defOf(it)?.usesMedia == true }
        if (!hasMedia) return
        try {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(appContext, MediaNotificationListener::class.java))
            sessionManager = manager
            watch(data.activeController())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun unregisterMedia() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        watch(null)
    }

    private fun watch(controller: MediaController?) {
        watched?.unregisterCallback(controllerCallback)
        watched = controller
        controller?.registerCallback(controllerCallback)
    }

    private fun mediaAction(action: Int) {
        val controller = data.activeController() ?: return openMediaAccess()
        val controls = controller.transportControls
        when (action) {
            CustomWidgetView.MEDIA_PREVIOUS -> controls.skipToPrevious()
            CustomWidgetView.MEDIA_NEXT -> controls.skipToNext()
            else -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
        }
    }

    private fun openMediaAccess() {
        if (MediaNotificationListener.isEnabled(context)) return
        context.showToast(context.getString(R.string.widget_media_permission_toast), Toast.LENGTH_LONG)
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun onCustomTap(itemId: String, tap: String, def: WidgetDef) {
        if (def.usesMedia && !MediaNotificationListener.isEnabled(context)) return openMediaAccess()
        when {
            tap == WidgetDef.TAP_CLOCK -> actions.openClock()
            tap == WidgetDef.TAP_CALENDAR -> actions.openCalendar()
            tap == WidgetDef.TAP_EDIT -> prefs.gridItems.firstOrNull { it.id == itemId }?.let(::editItemText)
            tap == WidgetDef.TAP_MEDIA -> openMediaApp()
            tap.startsWith(WidgetDef.TAP_APP_PREFIX) ->
                actions.launchApp("", tap.removePrefix(WidgetDef.TAP_APP_PREFIX), null, android.os.Process.myUserHandle().toString())
        }
    }

    private fun openMediaApp() {
        val controller = data.activeController() ?: return
        try {
            controller.sessionActivity?.send()
                ?: context.packageManager.getLaunchIntentForPackage(controller.packageName)?.let(context::startActivity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------------------------
    // Edit mode
    // ---------------------------------------------------------------------------------

    fun enterEditMode(announce: Boolean = true) {
        if (!prefs.gridEnabled) {
            context.showToast(context.getString(R.string.grid_disabled_hint), Toast.LENGTH_LONG)
            return
        }
        if (editing) return
        editing = true
        grid.editMode = true
        doneButton.isVisible = true
        actions.onEditModeChanged(true)
        if (announce) context.showToast(context.getString(R.string.grid_edit_hint), Toast.LENGTH_LONG)
    }

    fun exitEditMode() {
        if (!editing) return
        editing = false
        grid.editMode = false
        doneButton.isVisible = false
        actions.onEditModeChanged(false)
    }

    override fun onItemClick(item: GridItem) {
        if (editing) return
        when (item.type) {
            GridItem.TYPE_APP -> actions.launchApp(
                prefs.getAppRenameLabel(item.pkg).ifEmpty { item.label }, item.pkg, item.cls.ifEmpty { null }, item.user,
            )

            GridItem.TYPE_SHORTCUT -> actions.launchShortcut(item.label, item.pkg, item.shortcutId, item.user)
            GridItem.TYPE_FOLDER -> actions.openFolder(item.folderId)
        }
    }

    override fun onItemLongClick(item: GridItem) {
        enterEditMode(announce = false)
        showItemMenu(item)
    }

    override fun onSwipe(direction: Int) {
        if (!editing) actions.onSwipe(direction)
    }

    override fun onEditItemTap(item: GridItem) = showItemMenu(item)

    override fun onEditEmptyCellTap(col: Int, row: Int) = showAddMenu(col, row)

    override fun onItemMoved(item: GridItem, col: Int, row: Int) {
        GridOps.update(prefs, item.copy(col = col, row = row))
        render()
    }

    private fun showItemMenu(item: GridItem) {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (item.type == GridItem.TYPE_APP) entries += context.getString(R.string.grid_change_icon) to {
            IconPickerDialog.show(context, item.pkg, item.user, prefs.getAppRenameLabel(item.pkg).ifEmpty { item.label }) { render() }
        }
        if (item.type == GridItem.TYPE_CUSTOM_WIDGET && defOf(item)?.tap == WidgetDef.TAP_EDIT)
            entries += context.getString(R.string.grid_edit_text) to { editItemText(item) }
        if (item.type == GridItem.TYPE_APP_WIDGET && widgetManager.getAppWidgetInfo(item.appWidgetId)?.configure != null)
            entries += context.getString(R.string.grid_reconfigure) to { reconfigure(item) }
        entries += context.getString(R.string.grid_resize) to { showResize(item) }
        entries += context.getString(R.string.grid_remove) to { removeItem(item) }
        IconPickerDialog.showList(context, itemTitle(item), entries.map { it.first }) { entries[it].second() }
    }

    private fun itemTitle(item: GridItem): String = when (item.type) {
        GridItem.TYPE_CUSTOM_WIDGET -> defOf(item)?.name() ?: item.label
        GridItem.TYPE_FOLDER -> prefs.getFolder(item.folderId)?.name ?: item.label
        else -> item.label
    }

    private fun showResize(item: GridItem) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
        }
        var spanX = item.spanX
        var spanY = item.spanY
        val card = SettingsBuilder.Card(context, container)
        card.sliderRow(context.getString(R.string.grid_width), null, 1, (prefs.gridColumns - item.col).coerceAtLeast(1), spanX, { it.toString() }) { spanX = it }
        card.sliderRow(context.getString(R.string.grid_height), null, 1, (prefs.gridRows - item.row).coerceAtLeast(1), spanY, { it.toString() }) { spanY = it }
        AlertDialog.Builder(context)
            .setTitle(R.string.grid_resize)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val items = prefs.gridItems
                if (HomeGrid.isFree(items, prefs.gridColumns, prefs.gridRows, item.col, item.row, spanX, spanY, item.id)) {
                    GridOps.update(prefs, item.copy(spanX = spanX, spanY = spanY))
                    render()
                } else {
                    context.showToast(context.getString(R.string.grid_no_space))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeItem(item: GridItem) {
        if (item.type == GridItem.TYPE_APP_WIDGET && item.appWidgetId != -1) {
            runCatching { widgetHost.deleteAppWidgetId(item.appWidgetId) }
            hostViews.remove(item.appWidgetId)
        }
        GridOps.remove(prefs, item.id)
        render()
        if (!hasCustomWidgets()) stopTicking()
    }

    fun clearAll() {
        for (item in prefs.gridItems) {
            if (item.type == GridItem.TYPE_APP_WIDGET && item.appWidgetId != -1)
                runCatching { widgetHost.deleteAppWidgetId(item.appWidgetId) }
        }
        hostViews.clear()
        prefs.gridItems = emptyList()
        stopTicking()
        render()
    }

    private fun editItemText(item: GridItem) {
        val input = EditText(context).apply {
            setText(item.data)
            setSelection(text.length)
            minLines = 2
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
        }
        val frame = LinearLayout(context).apply {
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_text_title)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val latest = prefs.gridItems.firstOrNull { it.id == item.id } ?: return@setPositiveButton
                GridOps.update(prefs, latest.copy(data = input.text.toString().take(2000)))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddMenu(col: Int, row: Int) {
        val entries = listOf<Pair<String, () -> Unit>>(
            context.getString(R.string.grid_add_app) to {
                prefs.gridPendingCell = "$col,$row"
                resumeEditing = true
                actions.pickAppForGrid()
            },
            context.getString(R.string.grid_add_folder) to { addFolder(col, row) },
            context.getString(R.string.grid_add_android_widget) to { startAndroidWidgetPick(col, row) },
            context.getString(R.string.grid_add_custom_widget) to { addCustomWidget(col, row) },
        )
        IconPickerDialog.showList(context, context.getString(R.string.grid_add_title), entries.map { it.first }) { entries[it].second() }
    }

    private fun addFolder(col: Int, row: Int) {
        val folders = prefs.folders
        if (folders.isEmpty()) {
            context.showToast(context.getString(R.string.grid_no_folders))
            return
        }
        IconPickerDialog.showList(context, context.getString(R.string.grid_add_folder), folders.map { it.name }) { index ->
            val folder = folders[index]
            if (GridOps.add(prefs, GridItem(type = GridItem.TYPE_FOLDER, col = col, row = row, folderId = folder.id, label = folder.name)))
                render()
            else context.showToast(context.getString(R.string.grid_full))
        }
    }

    private fun addCustomWidget(col: Int, row: Int) {
        val defs = WidgetDef.Library.all(context, prefs)
        IconPickerDialog.showList(context, context.getString(R.string.grid_add_custom_widget), defs.map { it.name() }) { index ->
            val def = defs[index]
            val item = GridItem(
                type = GridItem.TYPE_CUSTOM_WIDGET, col = col, row = row,
                spanX = def.spanX.coerceAtMost(prefs.gridColumns), spanY = def.spanY.coerceAtMost(prefs.gridRows),
                widgetDefId = def.id, label = def.name(),
            )
            if (!GridOps.add(prefs, item)) {
                context.showToast(context.getString(R.string.grid_no_space))
                return@showList
            }
            if (def.uses("event") && !data.calendarPermission()) actions.requestCalendarPermission()
            if (def.usesMedia) openMediaAccess()
            render()
            startTicking()
        }
    }

    // ---------------------------------------------------------------------------------
    // Android widgets: pick → (bind permission) → (configure) → place
    // ---------------------------------------------------------------------------------

    private data class Pending(val id: Int, val col: Int, val row: Int, val replaceId: String)

    private fun pending(): Pending? {
        val parts = prefs.gridPendingWidget.split(',')
        if (parts.size < 3) return null
        return Pending(
            parts[0].toIntOrNull() ?: return null,
            parts[1].toIntOrNull() ?: 0,
            parts[2].toIntOrNull() ?: 0,
            parts.getOrNull(3).orEmpty(),
        )
    }

    private fun setPending(id: Int, col: Int, row: Int, replaceId: String = "") {
        prefs.gridPendingWidget = "$id,$col,$row,$replaceId"
    }

    private fun startAndroidWidgetPick(col: Int, row: Int) {
        if (!isOlauncherDefault(context)) {
            context.showToast(context.getString(R.string.widget_requires_default_launcher), Toast.LENGTH_LONG)
            return
        }
        val id = widgetHost.allocateAppWidgetId()
        setPending(id, col, row)
        val pick = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList<Parcelable>())
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList<Parcelable>())
        }
        if (pick.resolveActivity(context.packageManager) != null) {
            try {
                actions.launchWidgetIntent(REQUEST_PICK, pick)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        showProviderPicker(id)
    }

    // Used when the OEM has no system widget picker.
    private fun showProviderPicker(id: Int) {
        val providers = widgetManager.installedProviders
        if (providers.isEmpty()) {
            cancelPending(id)
            context.showToast(context.getString(R.string.no_widgets_available))
            return
        }
        val pm = context.packageManager
        val dialog = IconPickerDialog.showList(context, context.getString(R.string.widget_choose), providers.map { it.loadLabel(pm) }) { index ->
            bindProvider(id, providers[index].provider)
        }
        dialog.setOnCancelListener { cancelPending(id) }
    }

    private fun bindProvider(id: Int, provider: ComponentName) {
        val allowed = runCatching { widgetManager.bindAppWidgetIdIfAllowed(id, provider) }.getOrDefault(false)
        if (allowed) {
            afterBound(id)
            return
        }
        try {
            actions.launchWidgetIntent(REQUEST_BIND, Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            })
        } catch (e: Exception) {
            cancelPending(id)
            context.showToast(context.getString(R.string.widget_bind_failed))
        }
    }

    private fun startRebind(item: GridItem) {
        val component = ComponentName.unflattenFromString(item.provider) ?: return removeItem(item)
        val id = widgetHost.allocateAppWidgetId()
        setPending(id, item.col, item.row, item.id)
        rebindFailed -= item.id
        bindProvider(id, component)
    }

    private fun reconfigure(item: GridItem) {
        val info = widgetManager.getAppWidgetInfo(item.appWidgetId) ?: return
        try {
            actions.launchWidgetIntent(REQUEST_RECONFIGURE, Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, item.appWidgetId)
            })
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.widget_bind_failed))
        }
    }

    /** Forwarded from the fragment's activity-result launchers. */
    fun onWidgetResult(request: Int, resultCode: Int, result: Intent?) {
        if (request == REQUEST_RECONFIGURE) {
            render()
            return
        }
        val pending = pending() ?: return
        val id = result?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it != -1 } ?: pending.id
        Log.d(TAG, "widget result request=$request code=$resultCode id=$id pending=${pending.id}")
        if (resultCode != Activity.RESULT_OK) {
            cancelPending(id)
            return
        }
        when (request) {
            REQUEST_PICK, REQUEST_BIND -> afterBound(id)
            REQUEST_CONFIGURE -> placeWidget(id)
        }
    }

    private fun afterBound(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: return cancelPending(id)
        if (info.configure == null) return placeWidget(id)
        try {
            actions.launchWidgetIntent(REQUEST_CONFIGURE, Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            })
        } catch (e: Exception) {
            placeWidget(id)
        }
    }

    private fun placeWidget(id: Int) {
        val pending = pending()
        val info = widgetManager.getAppWidgetInfo(id)
        Log.d(TAG, "place widget id=$id pending=$pending bound=${info != null}")
        if (pending == null || info == null) return cancelPending(id)
        prefs.gridPendingWidget = ""
        val columns = prefs.gridColumns
        val rows = prefs.gridRows
        val cellW = grid.cellWidth.takeIf { it > 0f } ?: (context.resources.displayMetrics.widthPixels.toFloat() / columns)
        val cellH = grid.cellHeight.takeIf { it > 0f } ?: (context.resources.displayMetrics.heightPixels.toFloat() / rows)
        val spanX = kotlin.math.ceil(info.minWidth / cellW).toInt().coerceIn(1, columns)
        val spanY = kotlin.math.ceil(info.minHeight / cellH).toInt().coerceIn(1, rows)

        val replaced = prefs.gridItems.firstOrNull { it.id == pending.replaceId }
        val item = (replaced?.copy(appWidgetId = id, provider = info.provider.flattenToString()))
            ?: GridItem(
                type = GridItem.TYPE_APP_WIDGET, col = pending.col, row = pending.row,
                spanX = spanX, spanY = spanY, appWidgetId = id,
                provider = info.provider.flattenToString(), label = info.loadLabel(context.packageManager),
            )
        if (replaced != null) {
            GridOps.update(prefs, item)
        } else if (!addShrinkingToFit(item)) {
            runCatching { widgetHost.deleteAppWidgetId(id) }
            context.showToast(context.getString(R.string.grid_full))
            return
        }
        render()
    }

    // A widget's minimum size may not fit a busy grid: rather than refusing it, place it
    // smaller (the user can resize it afterwards). Largest area wins.
    private fun addShrinkingToFit(item: GridItem): Boolean {
        val sizes = (item.spanX downTo 1).flatMap { sx -> (item.spanY downTo 1).map { sy -> sx to sy } }
            .sortedByDescending { (sx, sy) -> sx * sy }
        for ((sx, sy) in sizes) {
            if (GridOps.add(prefs, item.copy(spanX = sx, spanY = sy))) return true
        }
        return false
    }

    private fun cancelPending(id: Int) {
        Log.d(TAG, "cancel pending widget id=$id")
        runCatching { widgetHost.deleteAppWidgetId(id) }
        prefs.gridPendingWidget = ""
    }

    /** A picker may have completed while our view was gone; adopt or discard its widget. */
    fun finalizeStalePending() {
        val pending = pending() ?: return
        if (widgetManager.getAppWidgetInfo(pending.id) != null) placeWidget(pending.id) else cancelPending(pending.id)
    }

    fun onDestroy() {
        stopTicking()
        hostViews.clear()
        grid.listener = null
    }

    companion object {
        const val REQUEST_PICK = 1
        const val REQUEST_BIND = 2
        const val REQUEST_CONFIGURE = 3
        const val REQUEST_RECONFIGURE = 4
        private const val PLACEHOLDER = "grid_placeholder"
        private const val TAG = "BLGrid"

        // Survives the trip to the app drawer (which recreates the home view) so the
        // user lands back in edit mode after picking an app for a cell.
        @Volatile
        private var resumeEditing = false
    }
}
