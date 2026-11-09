package com.edgetts.engine

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView

/**
 * Dropdown adapter whose filter matches every typed word anywhere in an
 * option's search text (so "south africa" or a voice name both work), unlike
 * ArrayAdapter's prefix match.
 */
class SearchableAdapter(
    context: Context,
    private val all: List<Option>,
) : BaseAdapter(), Filterable {

    class Option(val value: String, val label: String, val detail: String?, searchText: String) {
        val searchKey = Languages.searchKey("$label $searchText")
        override fun toString() = label
    }

    private val inflater = LayoutInflater.from(context)
    private var shown: List<Option> = all

    /** Label of the current choice; typing it unchanged shows the full list. */
    var selectedLabel: String? = null

    fun showAll() {
        shown = all
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int): Option = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_language, parent, false)
        val option = shown[position]
        val selected = option.label == selectedLabel
        view.findViewById<TextView>(R.id.title).text = option.label
        view.findViewById<TextView>(R.id.subtitle).apply {
            text = option.detail
            visibility = if (option.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.selectedMark).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE
        view.contentDescription = listOfNotNull(
            option.label,
            option.detail,
            if (selected) view.context.getString(R.string.selected) else null,
        ).joinToString(", ")
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString().orEmpty()
            val terms = Languages.searchKey(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val matches = if (terms.isEmpty() || query == selectedLabel) all
            else all.filter { option -> terms.all { it in option.searchKey } }
            return FilterResults().apply { values = matches; count = matches.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            shown = results.values as List<Option>
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?) = (resultValue as Option).label
    }
}
