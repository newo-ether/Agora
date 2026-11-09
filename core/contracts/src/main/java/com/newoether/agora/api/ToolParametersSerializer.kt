package com.newoether.agora.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder

/**
 * Wire form of [ToolParameters]. An external schema ([ToolParameters.schema]) is written exactly as
 * declared; otherwise the typed fields are written the same way the generated serializer wrote them.
 */
internal object ToolParametersSerializer : KSerializer<ToolParameters> {
    @Serializable
    private data class Typed(
        val type: String = "object",
        val properties: Map<String, ToolProperty>,
        val required: List<String> = emptyList(),
    )

    override val descriptor: SerialDescriptor = Typed.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ToolParameters) {
        val schema = value.schema
        if (schema != null && encoder is JsonEncoder) {
            encoder.encodeJsonElement(schema)
        } else {
            encoder.encodeSerializableValue(
                Typed.serializer(),
                Typed(type = value.type, properties = value.properties, required = value.required),
            )
        }
    }

    override fun deserialize(decoder: Decoder): ToolParameters {
        val typed = decoder.decodeSerializableValue(Typed.serializer())
        return ToolParameters(type = typed.type, properties = typed.properties, required = typed.required)
    }
}
