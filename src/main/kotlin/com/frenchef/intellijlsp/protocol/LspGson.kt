package com.frenchef.intellijlsp.protocol

import com.frenchef.intellijlsp.protocol.models.*
import com.google.gson.*
import java.lang.reflect.Type

/**
 * A unified Gson instance configured with enum serializers required by the LSP protocol.
 * The LSP protocol requires enums to be serialized as their values rather than their names.
 */
object LspGson {
    val instance: Gson by lazy {
        GsonBuilder()
            // Serialize enums using their value fields (required by the LSP specification).
            .registerTypeAdapter(SymbolKind::class.java, EnumValueSerializer<SymbolKind>())
            .registerTypeAdapter(SymbolKind::class.java, SymbolKindDeserializer())
            .registerTypeAdapter(CompletionItemKind::class.java, EnumValueSerializer<CompletionItemKind>())
            .registerTypeAdapter(CompletionItemKind::class.java, CompletionItemKindDeserializer())
            .registerTypeAdapter(DiagnosticSeverity::class.java, EnumValueSerializer<DiagnosticSeverity>())
            .registerTypeAdapter(DiagnosticSeverity::class.java, DiagnosticSeverityDeserializer())
            .registerTypeAdapter(TextDocumentSyncKind::class.java, EnumValueSerializer<TextDocumentSyncKind>())
            .registerTypeAdapter(InsertTextFormat::class.java, EnumValueSerializer<InsertTextFormat>())
            .registerTypeAdapter(CompletionTriggerKind::class.java, EnumValueSerializer<CompletionTriggerKind>())
            .registerTypeAdapter(MarkupKind::class.java, MarkupKindSerializer())
            .registerTypeAdapter(MarkupKind::class.java, MarkupKindDeserializer())
            .create()
    }
}

/**
 * Generic enum serializer that serializes an enum using its value field.
 */
private class EnumValueSerializer<T : Enum<*>> : JsonSerializer<T> {
    override fun serialize(src: T?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        if (src == null) return JsonNull.INSTANCE
        // Try to get the value field.
        return try {
            val valueField = src.javaClass.getDeclaredField("value")
            valueField.isAccessible = true
            when (val value = valueField.get(src)) {
                is Int -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                else -> JsonPrimitive(src.name)
            }
        } catch (e: Exception) {
            JsonPrimitive(src.name)
        }
    }
}

/**
 * SymbolKind deserializer.
 */
private class SymbolKindDeserializer : JsonDeserializer<SymbolKind> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): SymbolKind? {
        if (json == null || json.isJsonNull) return null
        val value = json.asInt
        return SymbolKind.entries.find { it.value == value }
    }
}

/**
 * CompletionItemKind deserializer.
 */
private class CompletionItemKindDeserializer : JsonDeserializer<CompletionItemKind> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): CompletionItemKind? {
        if (json == null || json.isJsonNull) return null
        val value = json.asInt
        return CompletionItemKind.entries.find { it.value == value }
    }
}

/**
 * DiagnosticSeverity deserializer.
 */
private class DiagnosticSeverityDeserializer : JsonDeserializer<DiagnosticSeverity> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): DiagnosticSeverity? {
        if (json == null || json.isJsonNull) return null
        val value = json.asInt
        return DiagnosticSeverity.entries.find { it.value == value }
    }
}

/**
 * MarkupKind serializer (value is a String).
 */
private class MarkupKindSerializer : JsonSerializer<MarkupKind> {
    override fun serialize(src: MarkupKind?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        if (src == null) return JsonNull.INSTANCE
        return JsonPrimitive(src.value)
    }
}

/**
 * MarkupKind deserializer.
 */
private class MarkupKindDeserializer : JsonDeserializer<MarkupKind> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): MarkupKind? {
        if (json == null || json.isJsonNull) return null
        val value = json.asString
        return MarkupKind.entries.find { it.value == value }
    }
}
