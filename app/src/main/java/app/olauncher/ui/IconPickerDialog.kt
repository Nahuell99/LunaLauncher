package app.olauncher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.IconManager
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.icons.IconPack
import app.olauncher.helper.showToast
import java.util.Locale

/**
 * Lets the user pick any drawable from any installed icon pack for one app, or go back
 * to the automatic icon. Two steps: choose a pack, then search its icons.
 */
object IconPickerDialog {

    fun show(context: Context, packageName: String, user: String, label: String, onChanged: () -> Unit) {
        val prefs = Prefs(context)
        val packs = IconPack.installedPacks(context)
        val options = listOf(context.getString(R.string.icon_auto)) + packs.map { it.label }

        showList(context, context.getString(R.string.icon_choose_for, label), options) { index ->
            if (index == 0) {
                prefs.setIconOverride(packageName, user, "")
                IconManager.clearCache()
                onChanged()
            } else {
                showPackIcons(context, IconManager.pack(context, packs[index - 1].packageName), label) { name ->
                    prefs.setIconOverride(packageName, user, "${packs[index - 1].packageName}/$name")
                    IconManager.clearCache()
                    onChanged()
                }
            }
        }
        if (packs.isEmpty()) context.showToast(context.getString(R.string.icon_pack_none_installed))
    }

    /** Simple themed single-choice list built on dialog_list_picker. */
    fun showList(context: Context, title: String, options: List<String>, onPick: (Int) -> Unit): AlertDialog {
        val view = View.inflate(context, R.layout.dialog_list_picker, null)
        val titleView = view.findViewById<TextView>(R.id.pickerTitle)
        val list = view.findViewById<LinearLayout>(R.id.pickerList)
        titleView.text = title
        titleView.visibility = View.VISIBLE
        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        options.forEachIndexed { index, option ->
            list.addView(TextView(context).apply {
                text = option
                textSize = 18f
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dpToPx(), 14.dpToPx(), 10.dpToPx(), 14.dpToPx())
                setBackgroundResource(ripple)
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }
        dialog.show()
        return dialog
    }

    private fun showPackIcons(context: Context, pack: IconPack, label: String, onPick: (String) -> Unit) {
        val primary = context.getColorFromAttr(R.attr.primaryColor)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 0)
        }
        val search = EditText(context).apply {
            hint = context.getString(R.string.icon_search_hint)
            setTextColor(primary)
            setHintTextColor(context.getColorFromAttr(R.attr.primaryColorTrans50))
            isSingleLine = true
        }
        val status = TextView(context).apply {
            text = context.getString(R.string.icon_loading)
            setTextColor(primary)
            setPadding(0, 16.dpToPx(), 0, 16.dpToPx())
            gravity = Gravity.CENTER
        }
        val grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = 64.dpToPx()
            verticalSpacing = 8.dpToPx()
            horizontalSpacing = 8.dpToPx()
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
        }
        root.addView(search)
        root.addView(status)
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 420.dpToPx()))

        var all: List<String> = emptyList()
        var shown: List<String> = emptyList()
        val itemSize = 56.dpToPx()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val image = (convertView as? ImageView) ?: ImageView(context).apply {
                    layoutParams = AbsListView.LayoutParams(itemSize, itemSize)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = 6.dpToPx()
                    setPadding(p, p, p, p)
                }
                image.setImageDrawable(pack.drawableByName(shown[position]))
                return image
            }
        }
        grid.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.icon_choose_for, label))
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            onPick(shown[position])
            dialog.dismiss()
        }

        fun filter(query: String) {
            val q = query.trim().lowercase(Locale.ROOT).replace(' ', '_')
            shown = if (q.isEmpty()) all else all.filter { it.contains(q) }
            status.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            status.text = context.getString(R.string.icon_none_found)
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })

        // Packs can hold thousands of names: parse off the main thread, then put the ones
        // that look like this app first.
        val words = label.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val names = pack.allDrawableNames()
            val sorted = names.sortedByDescending { name -> words.count { name.contains(it) } }
            main.post {
                all = sorted
                filter(search.text.toString())
            }
        }.start()
        dialog.show()
    }
}
