package io.github.tritium_launcher.launcher.model.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("IntRange") {
            element<Int>("start")
            element<Int>("endInclusive")
        }

    override fun serialize(encoder: Encoder, value: IntRange) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeIntElement(descriptor, 0, value.first)
        composite.encodeIntElement(descriptor, 1, value.last)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val dec = decoder.beginStructure(descriptor)
        var start = 0
        var endInclusive = 0
        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                0 -> start = dec.decodeIntElement(descriptor, 0)
                1 -> endInclusive = dec.decodeIntElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> throw SerializationException("Unexpected index: $index")
            }
        }
        dec.endStructure(descriptor)
        return start..endInclusive
    }
}