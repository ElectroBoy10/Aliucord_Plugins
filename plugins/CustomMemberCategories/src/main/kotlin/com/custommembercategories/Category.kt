package com.custommembercategories

import org.json.JSONArray
import org.json.JSONObject

/** One local-only category: a name plus the roles whose online members are shown under it. */
class Category(
    val id: String,
    var name: String,
    val roleIds: MutableList<Long> = mutableListOf(),
    var matchAll: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("matchAll", matchAll)
        put("roles", JSONArray().also { array -> roleIds.forEach { array.put(it) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): Category {
            val roles = mutableListOf<Long>()
            obj.optJSONArray("roles")?.let { array ->
                for (i in 0 until array.length()) roles.add(array.getLong(i))
            }
            return Category(
                id = obj.getString("id"),
                name = obj.optString("name", "Category"),
                roleIds = roles,
                matchAll = obj.optBoolean("matchAll", false),
            )
        }

        fun parseList(json: String): MutableList<Category> {
            val result = mutableListOf<Category>()
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) result.add(fromJson(array.getJSONObject(i)))
            } catch (_: Throwable) {
                // Corrupt or empty data: start with no categories.
            }
            return result
        }

        fun toJsonString(list: List<Category>): String =
            JSONArray().also { array -> list.forEach { array.put(it.toJson()) } }.toString()
    }
}
