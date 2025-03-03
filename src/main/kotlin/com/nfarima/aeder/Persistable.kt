package com.nfarima.aeder

import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


abstract class Persistable<T>(
    private val initialValue: T,
    private val name: String,
    private val afterSet: (T) -> Unit = {}
) : ReadWriteProperty<Any?, T> {
    internal val json = Gson()

    val file = File("$workingDir/$name").apply {
        if (createNewFile()) {
            writeText(json.toJson(initialValue))
        }
    }

    init {
        require(name.isNotBlank()) { "Name cannot be blank" }
    }

    private var cached: T? = null
    override operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val name = name ?: property.name
        cached?.let { return it }
        return get(name)
    }

    override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val name = name ?: property.name
        cached = value
        set(name, value)
        afterSet(value)
    }

    abstract fun get(name: String): T
    abstract fun set(name: String, value: T)
}

inline fun <reified T : Any> persisted(
    initialValue: T,
    name: String,
): Persistable<T> {
    return object : Persistable<T>(initialValue, name) {


        override fun get(name: String): T {

            val jsonString = file.readText().trim()
            return if (jsonString.isNotEmpty()) json.fromJson(jsonString, T::class.java) else initialValue
        }

        override fun set(name: String, value: T) {
            file.writeText(json.toJson(value))
        }
    }
}
