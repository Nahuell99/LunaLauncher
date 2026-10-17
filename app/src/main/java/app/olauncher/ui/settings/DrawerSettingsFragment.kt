package app.olauncher.ui.settings

import app.olauncher.R
import app.olauncher.helper.DrawerOrganizer
import app.olauncher.ui.IconPickerDialog

/** Drawer order and category grouping, plus the search extras (calculator, units, commands). */
class DrawerSettingsFragment : SubSettingsFragment() {

    override val titleRes = R.string.drawer_settings_title

    override fun build(builder: SettingsBuilder) {
        builder.card(getString(R.string.drawer_settings_title)) {
            val orders = listOf(R.string.drawer_sort_alpha, R.string.drawer_sort_used, R.string.drawer_sort_recent).map(::getString)
            valueRow(getString(R.string.drawer_order), R.drawable.ic_sc_grid, orders[prefs.drawerSort.coerceIn(0, orders.lastIndex)]) {
                IconPickerDialog.showList(requireContext(), getString(R.string.drawer_order), orders) { index ->
                    prefs.drawerSort = index.coerceIn(DrawerOrganizer.SORT_ALPHA, DrawerOrganizer.SORT_RECENT)
                    rebuild()
                }
            }
            switchRow(getString(R.string.drawer_group), R.drawable.ic_sc_folder, prefs.drawerGroupByCategory) {
                prefs.drawerGroupByCategory = it
            }
            hint(getString(R.string.drawer_group_hint))
        }

        builder.card(getString(R.string.search_extras_title)) {
            switchRow(getString(R.string.search_calculator), R.drawable.ic_sc_calculator, prefs.searchCalculator) {
                prefs.searchCalculator = it
            }
            hint(getString(R.string.search_calculator_hint))
            switchRow(getString(R.string.search_commands), R.drawable.ic_sc_search, prefs.searchCommands) {
                prefs.searchCommands = it
            }
            hint(getString(R.string.search_commands_hint))
        }
    }
}
