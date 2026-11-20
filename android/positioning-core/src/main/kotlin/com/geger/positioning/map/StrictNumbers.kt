package com.geger.positioning.map

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Strict number reading for map.json.
 *
 * Concept: kotlinx.serialization happily reads a quoted value like "x": "1" as the number 1. The
 * browser map editor rejects that (a number typed as text usually means a hand-editing mistake),
 * and both must agree on which maps are valid (see FixtureParityTest). These serializers accept
 * only real JSON numbers and are applied to every Double and Int in MapFile.kt via @UseSerializers.
 */

/** Reads the current JSON value as a primitive that is NOT a string, or explains why not. */
private fun Decoder.numericPrimitive(expected: String): JsonPrimitive {
    val json = this as? JsonDecoder ?: throw SerializationException("map files can only be read as JSON")
    val element = json.decodeJsonElement()
    val primitive = element as? JsonPrimitive ?: throw SerializationException("expected $expected, found $element")
    if (primitive.isString) throw SerializationException("expected $expected, found text \"${primitive.content}\"")
    return primitive
}

/** A Double that must be written as a JSON number. */
object StrictDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val p = decoder.numericPrimitive("a number")
        return p.doubleOrNull ?: throw SerializationException("expected a number, found ${p.content}")
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** An Int that must be written as a whole JSON number. */
object StrictIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val p = decoder.numericPrimitive("a whole number")
        return p.intOrNull ?: throw SerializationException("expected a whole number, found ${p.content}")
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}
