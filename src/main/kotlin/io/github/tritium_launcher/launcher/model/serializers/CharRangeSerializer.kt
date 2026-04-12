package io.github.tritium_launcher.launcher.model.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object CharRangeSerializer : KSerializer<CharRange> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CharRange") {
        element<String>("start")
        element<String>("endInclusive")
    }
    override fun serialize(encoder: Encoder, value: CharRange) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.first.toString())
        composite.encodeStringElement(descriptor, 1, value.last.toString())
        composite.endStructure(descriptor)
    }
    override fun deserialize(decoder: Decoder): CharRange {
        val dec = decoder.beginStructure(descriptor)
        var start = ' '
        var endInclusive = ' '
        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                0 -> start = dec.decodeStringElement(descriptor, 0).first()
                1 -> endInclusive = dec.decodeStringElement(descriptor, 1).first()
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> throw SerializationException("Unexpected index: $index")
            }
        }
        dec.endStructure(descriptor)
        return start..endInclusive
    }
}