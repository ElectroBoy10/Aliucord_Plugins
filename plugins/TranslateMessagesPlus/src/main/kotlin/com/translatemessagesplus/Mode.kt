package com.translatemessagesplus

/** Which messages of a chat get translated automatically. */
enum class Mode(val id: String, val label: String) {
    OFF("off", "Off"),
    EVERYONE("everyone", "Everyone"),
    OTHERS("others", "Others only"),
    MINE("mine", "My messages only"),
    ;

    /** Whether a message should be translated in this mode, given whether you sent it. */
    fun applies(own: Boolean): Boolean =
        when (this) {
            OFF -> false
            EVERYONE -> true
            OTHERS -> !own
            MINE -> own
        }

    companion object {
        fun fromId(id: String?): Mode? = values().firstOrNull { it.id == id }
    }
}
