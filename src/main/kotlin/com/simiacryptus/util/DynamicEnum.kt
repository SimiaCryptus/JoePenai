package com.simiacryptus.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.ser.std.StdSerializer

open class DynamicEnum<T : DynamicEnum<T>>(val name: String) {
    companion object {
        private val registries = mutableMapOf<Class<*>, MutableList<Pair<String, DynamicEnum<*>>>>()

        internal fun <T> getRegistry(clazz: Class<T>): MutableList<Pair<String, T>> {
            @Suppress("UNCHECKED_CAST")
            return registries.getOrPut(clazz) { mutableListOf() } as MutableList<Pair<String, T>>
        }

        fun <T> valueOf(clazz: Class<T>, name: String): T {
            return getRegistry(clazz).toMap().get(name)
                ?: throw IllegalArgumentException("Unknown enum constant: $name")
        }

        fun <T : DynamicEnum<T>> values(clazz: Class<T>): List<T> {
            return getRegistry(clazz).map { it.second }
        }

        @JvmStatic
        fun <T : DynamicEnum<T>> register(clazz: Class<T>, enumConstant: T) {
            getRegistry(clazz).add(enumConstant.name to enumConstant)
        }
    }

    override fun toString() = name
    override fun hashCode() = name.hashCode()
    override fun equals(other: Any?): Boolean {
        return this === other || other is DynamicEnum<*> && name == other.name
    }
}

abstract class DynamicEnumSerializer<T : DynamicEnum<T>>(
    private val clazz: Class<T>
) : StdSerializer<T>(clazz) {
    override fun serialize(value: T, gen: JsonGenerator, provider: SerializerProvider) {
        DynamicEnum.getRegistry(clazz).find { it.second == value }?.first?.let { gen.writeString(it) }
    }
}

abstract class DynamicEnumDeserializer<T : DynamicEnum<T>>(
    private val clazz: Class<T>
) : JsonDeserializer<T>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        return when (val node = p.codec.readTree<JsonNode>(p)) {
            is TextNode -> DynamicEnum.getRegistry(clazz).toMap()[node.asText()]
                ?: throw JsonMappingException(p, "Unknown enum constant: " + node.asText())

            is ObjectNode -> DynamicEnum.getRegistry(clazz).toMap()[node.get("name")?.asText()]
                ?: throw JsonMappingException(p, "Unknown enum constant: " + node.toPrettyString())

            else -> throw JsonMappingException(p, "Unexpected JSON value type: ${node.nodeType}")
        }
    }
}


