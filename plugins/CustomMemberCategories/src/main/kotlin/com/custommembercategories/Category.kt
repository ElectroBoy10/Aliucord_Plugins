package com.custommembercategories

import org.json.JSONArray
import org.json.JSONObject

/** One local-only category: a name plus the roles whose members are shown under it (online only, unless includeOffline is on). */
class Category(
    val id: String,
    var name: String,
    val roleIds: MutableList<Long> = mutableListOf(),
    var matchAll: Boolean = false,
    var includeOffline: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("matchAll", matchAll)
        put("includeOffline", includeOffline)
        put("roles", JSONArray().also { array -> roleIds.forEach { array.put(it) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): Category {
            val roles = mutableListOf<Long>()
            obj.optJSONArray("roles")?.let { array ->
                var i = 0
                while (i < array.length()) {
                    roles.add(array.getLong(i))
                    i++
                }
            }
            return Category(
                id = obj.getString("id"),
                name = obj.optString("name", "Category"),
                roleIds = roles,
                matchAll = obj.optBoolean("matchAll", false),
                includeOffline = obj.optBoolean("includeOffline", false),
            )
        }

        fun parseList(json: String): MutableList<Category> {
            val result = mutableListOf<Category>()
            try {
                val array = JSONArray(json)
                var i = 0
                while (i < array.length()) {
                    result.add(fromJson(array.getJSONObject(i)))
                    i++
                }
            } catch (_: Throwable) {
                // Corrupt or empty data: start with no categories.
            }
            return result
        }

        fun toJsonString(list: List<Category>): String =
            JSONArray().also { array -> list.forEach { array.put(it.toJson()) } }.toString()
    }
}
