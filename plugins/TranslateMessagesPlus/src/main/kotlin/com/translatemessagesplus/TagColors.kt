package com.translatemessagesplus

/** Colours for the "(translated DE → EN)" tag. A null colour means Discord's muted text colour. */
object TagColors {
    class Option(val id: String, val label: String, val color: Int?)

    val all: List<Option> = listOf(
        Option("blue", "Blue", 0xFF4F9CF9.toInt()),
        Option("green", "Green", 0xFF3BA55D.toInt()),
        Option("orange", "Orange", 0xFFF0932B.toInt()),
        Option("pink", "Pink", 0xFFEB459E.toInt()),
        Option("purple", "Purple", 0xFFA66BE0.toInt()),
        Option("muted", "Muted grey (like \"edited\")", null),
    )

    fun find(id: String?): Option = all.firstOrNull { it.id == id } ?: all[0]
}
